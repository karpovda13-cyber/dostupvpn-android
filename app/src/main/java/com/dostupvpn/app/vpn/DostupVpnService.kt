package com.dostupvpn.app.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import com.dostupvpn.app.MainActivity
import com.dostupvpn.app.R
import com.dostupvpn.app.data.SecureStore
import com.dostupvpn.app.diag.AppError
import com.dostupvpn.app.diag.Errors
import com.dostupvpn.app.diag.EventLog
import com.dostupvpn.app.net.ApiClient
import com.dostupvpn.app.net.ApiException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray
import java.io.RandomAccessFile

/**
 * VPN-сервис: поднимает системный TUN и отдаёт его дескриптор Xray-core
 * (inbound "tun" в конфиге). Свой пакет исключён из VPN (addDisallowedApplication),
 * поэтому исходящие сокеты Xray и запросы к API идут напрямую, без петли в туннель.
 *
 * Долгая работа в фоне: пока телефон «спит», heartbeat не доходит, и сервер удаляет конфиг.
 * Проснувшись, служба получает 410 и сама создаёт новую сессию. Если сети ещё нет — не сдаётся,
 * а ждёт её возвращения (с повторами), и лишь окончательные ошибки (401/402) останавливают VPN.
 */
class DostupVpnService : VpnService() {

    companion object {
        private const val TAG = "DostupVpnService"
        private const val NOTIFICATION_CHANNEL = "vpn"
        private const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "com.dostupvpn.app.STOP"
        const val EXTRA_CONFIG = "config"
        const val EXTRA_HEARTBEAT_SEC = "heartbeat_sec"

        /** true, пока служба жива. После убийства процесса системой сбрасывается в false вместе с ним. */
        @Volatile
        var running = false
            private set

        /**
         * После неудачного heartbeat повторяем быстро — сеть могла просто ещё не проснуться: 2, 4, 8 с,
         * дальше каждые 15 с. Возвращение сети всё равно прерывает ожидание (см. [wake]).
         */
        private val HEARTBEAT_RETRY_STEPS_MS = longArrayOf(2_000L, 4_000L, 8_000L, 15_000L)

        /** Несколько событий пробуждения подряд (экран, разблокировка, сеть) дают одну проверку, а не три. */
        private const val MIN_WAKE_GAP_MS = 5_000L
        private const val RENEW_BACKOFF_STEP_MS = 2_000L
        private const val RENEW_BACKOFF_MAX_MS = 30_000L

        /**
         * Если с прошлого включения экрана телефон провёл в глубоком сне дольше этого, соединения до сервера
         * и приложений, скорее всего, «забыты» NAT оператора/роутера. Ждать их таймаутов минутами не нужно:
         * при включении экрана ядро перезапускается (то же, что «выключить и включить»), и всё идёт заново.
         */
        private const val SOFT_RESET_AFTER_SLEEP_MS = 120_000L
        private const val MAX_CORE_LOG_BYTES = 1_000_000L
    }

    private var controller: CoreController? = null
    private var tun: ParcelFileDescriptor? = null
    private var heartbeatJob: Job? = null
    private var heartbeatSec = 180
    private var sessionStartedAt = 0L
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    private var wakeReceiver: BroadcastReceiver? = null

    /** Конфиг текущей сессии — нужен для быстрого перезапуска ядра без запроса новой сессии. */
    @Volatile
    private var lastConfig: String? = null

    /** Сеть, по которой работали до сих пор. Если она сменилась (Wi-Fi <-> LTE), старые соединения мертвы. */
    @Volatile
    private var lastNetwork: Network? = null

    /** Выставляются слушателями и сбрасываются проверкой в [startHeartbeat]. */
    @Volatile
    private var networkSwitched = false

    @Volatile
    private var screenWake = false

    /** Сколько миллисекунд устройство провело в глубоком сне с начала загрузки (на момент последней проверки). */
    private var sleepBaselineMs = 0L

    /** Сигнал «проснуться и проверить связь сейчас» (например, вернулась сеть). */
    private val wake = Channel<Unit>(Channel.CONFLATED)

    private val serviceScope = CoroutineScope(Dispatchers.IO)
    private val sessionStore by lazy { VpnSessionStore(this) }
    private val api by lazy { ApiClient(SecureStore(this), this) }

    private val coreCallback = object : CoreCallbackHandler {
        override fun startup(): Long = 0
        override fun shutdown(): Long = 0
        override fun onEmitStatus(code: Long, message: String?): Long {
            Log.d(TAG, "xray: $message")
            return 0
        }
    }

    override fun onCreate() {
        super.onCreate()
        running = true
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopVpn()
            stopSelf()
            return START_NOT_STICKY
        }
        val config = intent?.getStringExtra(EXTRA_CONFIG) ?: run {
            stopSelf()
            return START_NOT_STICKY
        }
        heartbeatSec = intent.getIntExtra(EXTRA_HEARTBEAT_SEC, 180).coerceAtLeast(30)
        // Запуск ядра блокирующий — не на главном потоке.
        serviceScope.launch { startTunnel(config) }
        return START_NOT_STICKY
    }

    /** [startedAt] — время начала подключения, если это перезапуск внутри той же сессии (таймер не сбрасывается). */
    @Synchronized
    private fun startTunnel(config: String, startedAt: Long = 0L) {
        teardown()
        try {
            if (prepare(this) != null) error("missing VPN permission")

            val builder = Builder()
                .setSession("DostupVPN")
                .setMtu(XrayConfig.TUN_MTU)
                .addAddress("172.19.0.1", 30)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("1.1.1.1")
                .addDnsServer("8.8.8.8")
                .addDisallowedApplication(packageName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) builder.setMetered(false)
            excludeBypassApps(builder)
            val pfd = builder.establish() ?: error("VPN not prepared or revoked")
            tun = pfd
            EventLog.add(this, "TUN-интерфейс создан")

            val core = Libv2ray.newCoreController(coreCallback)
            core.startLoop(config, pfd.fd)
            if (!core.isRunning) error("ядро Xray не запустилось, см. xray.log")
            controller = core
            lastConfig = config
            networkSwitched = false
            screenWake = false
            sleepBaselineMs = deepSleepMs()
            EventLog.add(this, "Ядро Xray запущено")

            val at = if (startedAt > 0L) startedAt else System.currentTimeMillis()
            sessionStartedAt = at
            sessionStore.markConnected(at)
            updateNotification(at, reconnecting = false)
            registerNetworkCallback()
            registerWakeReceiver()
            startHeartbeat()
        } catch (e: Exception) {
            val error = Errors.vpn(e)
            Log.e(TAG, "не удалось запустить VPN: ${error.code}", e)
            fail(error)
        }
    }

    /**
     * Приложения из списка администратора (bypass_apps) работают мимо VPN. Запись — точное имя пакета
     * или маска "префикс.*": для масок перебираются установленные на телефоне пакеты. Не найденные
     * пропускаются. Имена пакетов в журнал не пишем — только количество.
     *
     * Список применяется при запуске туннеля: приложение, установленное во время работы VPN,
     * подхватится при следующем подключении.
     */
    private fun excludeBypassApps(builder: Builder) {
        val rules = AdminRulesStore.load(this).bypassApps
        if (rules.isEmpty()) {
            sessionStore.setBypassApplied(0)
            return
        }
        val installed = installedPackageNames()
        val byLower = installed.associateBy { it.lowercase() }   // имя в нижнем регистре -> настоящее имя на телефоне
        val prefixes = ArrayList<String>()
        val targets = LinkedHashSet<String>()
        for (rule in rules) {
            if (rule.endsWith(".*")) {
                prefixes += rule.dropLast(1).lowercase()          // "ru.*" -> "ru."
            } else if (installed.isEmpty()) {
                targets += rule                                    // список приложений получить не удалось — пробуем напрямую
            } else {
                byLower[rule.lowercase()]?.let { targets += it }   // берём настоящее имя: регистр в списках бывает искажён
            }
        }
        if (prefixes.isNotEmpty()) {
            for (pkg in installed) {
                val lower = pkg.lowercase()
                if (prefixes.any { lower.startsWith(it) }) targets += pkg
            }
        }
        targets.remove(packageName)

        var applied = 0
        for (pkg in targets) {
            try {
                builder.addDisallowedApplication(pkg)
                applied++
            } catch (_: PackageManager.NameNotFoundException) {
                // приложения нет на этом телефоне (или оно не видно системе) — это нормально
            }
        }
        sessionStore.setBypassApplied(applied)
        EventLog.add(this, "Приложений вне VPN: $applied (записей в списке: ${rules.size}, масок: ${prefixes.size})")
    }

    /** Имена всех установленных пакетов. Нужно разрешение QUERY_ALL_PACKAGES (с Android 11 без него список урезан). */
    private fun installedPackageNames(): List<String> = try {
        val apps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getInstalledApplications(0)
        }
        apps.map { it.packageName }
    } catch (e: Exception) {
        Log.w(TAG, "не удалось получить список приложений", e)
        emptyList()
    }

    /** Останавливает ядро и закрывает TUN. Состояние UI и сервер не трогает. */
    @Synchronized
    private fun teardown() {
        heartbeatJob?.cancel()
        heartbeatJob = null
        unregisterNetworkCallback()
        unregisterWakeReceiver()
        controller?.let { runCatching { it.stopLoop() } }
        controller = null
        // Android-версия TUN в Xray дескриптор не закрывает — владелец только мы.
        tun?.let { runCatching { it.close() } }
        tun = null
    }

    /** Штатная остановка (кнопка «отключить» в приложении или в уведомлении). */
    private fun stopVpn() {
        teardown()
        sessionStore.clear()
        serviceScope.launch { runCatching { api.sessionStop() } }
    }

    /** Окончательная остановка с сообщением для UI (сообщение не затирается). */
    private fun fail(error: AppError) {
        EventLog.add(this, "ОШИБКА ${error.code}: ${error.title}${if (error.detail.isBlank()) "" else " | ${error.detail}"}")
        teardown()
        sessionStore.markFailed(error)
        serviceScope.launch { runCatching { api.sessionStop() } }
        stopSelf()
    }

    /** Время глубокого сна (CPU выключен) с начала загрузки: elapsedRealtime идёт во сне, uptimeMillis — нет. */
    private fun deepSleepMs(): Long = SystemClock.elapsedRealtime() - SystemClock.uptimeMillis()

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = serviceScope.launch {
            var waitMs = heartbeatSec * 1000L
            var failures = 0
            var lastAttempt = 0L   // SystemClock.elapsedRealtime(): идёт и во время сна, в отличие от таймеров корутин
            while (isActive) {
                // Ждём либо таймер, либо сигнал пробуждения (экран, разблокировка, выход из Doze, сеть).
                // Таймер корутин замирает, пока телефон спит, поэтому без сигналов проверка после сна
                // откладывалась бы на оставшуюся часть интервала.
                val woken = withTimeoutOrNull(waitMs) { wake.receive(); true } ?: false
                val now = SystemClock.elapsedRealtime()
                val pending = networkSwitched || screenWake
                if (woken && !pending && lastAttempt != 0L && now - lastAttempt < MIN_WAKE_GAP_MS) continue
                if (woken && lastAttempt != 0L && now - lastAttempt > 60_000L) {
                    EventLog.add(this@DostupVpnService, "Пробуждение: проверяю сессию (прошло ${(now - lastAttempt) / 1000} с)")
                }
                lastAttempt = now
                trimCoreLog()
                try {
                    api.heartbeat()
                    waitMs = heartbeatSec * 1000L
                    failures = 0
                } catch (e: ApiException) {
                    when (e.status) {
                        410 -> {           // сервер удалил конфиг (долго не было heartbeat) — создаём новый
                            EventLog.add(this@DostupVpnService, "Сервер удалил сессию (410) — восстанавливаем")
                            renewSession()
                            return@launch
                        }
                        401, 402 -> {
                            fail(Errors.fromApi(e))
                            return@launch
                        }
                        else -> {
                            Log.w(TAG, "heartbeat error: ${e.message}")
                            waitMs = retryDelay(failures++)
                        }
                    }
                    continue
                } catch (e: Exception) {
                    // Нет сети (телефон только проснулся и т.п.): туннель не трогаем, повторим скоро.
                    Log.w(TAG, "heartbeat network error", e)
                    waitMs = retryDelay(failures++)
                    continue
                }

                // Сессия на сервере жива. Теперь — не «забыли» ли соединения, пока телефон спал или менялась сеть.
                val switched = networkSwitched
                val screen = screenWake
                networkSwitched = false
                screenWake = false
                if (!switched && !screen) continue
                val current = deepSleepMs()
                val sleptMs = current - sleepBaselineMs
                sleepBaselineMs = current
                val reason = when {
                    switched -> "сменилась сеть"
                    sleptMs >= SOFT_RESET_AFTER_SLEEP_MS -> "телефон спал ${sleptMs / 1000} с"
                    else -> null
                }
                if (reason != null) {
                    softRestart(reason)
                    return@launch
                }
            }
        }
    }

    private fun retryDelay(failures: Int): Long =
        HEARTBEAT_RETRY_STEPS_MS[failures.coerceAtMost(HEARTBEAT_RETRY_STEPS_MS.lastIndex)]

    /**
     * Перезапуск ядра с тем же конфигом, без новой сессии: старые соединения обрываются сразу
     * (приложения тут же открывают новые), а не висят до своих таймаутов.
     */
    private fun softRestart(reason: String) {
        val config = lastConfig ?: return
        EventLog.add(this, "Перезапуск туннеля: $reason")
        startTunnel(config, sessionStartedAt)   // сам запустит новый heartbeat
    }

    /**
     * Создаёт новую сессию и перезапускает ядро. Не сдаётся при сетевых ошибках: телефон мог
     * проснуться раньше, чем появилась сеть. Окончательные ошибки — только 401/402.
     */
    private suspend fun renewSession() {
        sessionStore.markReconnecting()
        updateNotification(sessionStartedAt, reconnecting = true)
        var waitMs = 0L
        var attempt = 0
        while (true) {
            if (waitMs > 0L) withTimeoutOrNull(waitMs) { wake.receive() }
            attempt++
            try {
                val session = api.sessionStart()
                // Пока шёл запрос, пользователь мог нажать «отключить» — не воскрешаем туннель.
                currentCoroutineContext().ensureActive()
                heartbeatSec = session.heartbeatSec.coerceAtLeast(30)
                val config = XrayConfig.build(this, session)
                EventLog.add(this, "Сессия обновлена (попытка $attempt)")
                startTunnel(config, sessionStartedAt)   // сам запустит новый heartbeat
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                if (e.status == 401 || e.status == 402) {
                    fail(Errors.fromApi(e))
                    return
                }
                EventLog.add(this, "Восстановление: попытка $attempt — ошибка ${e.status}")
            } catch (e: Exception) {
                EventLog.add(this, "Восстановление: попытка $attempt — нет связи")
            }
            waitMs = minOf(RENEW_BACKOFF_STEP_MS * attempt, RENEW_BACKOFF_MAX_MS)
        }
    }

    /** Возвращение сети (после сна, переключение Wi-Fi/мобильная сеть) — сразу проверяем связь, а не ждём таймер. */
    private fun registerNetworkCallback() {
        unregisterNetworkCallback()
        val cm: ConnectivityManager = getSystemService() ?: return
        lastNetwork = null   // первый вызов после регистрации лишь запоминает текущую сеть
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val previous = lastNetwork
                lastNetwork = network
                if (previous != null && previous != network) networkSwitched = true
                wake.trySend(Unit)
            }
        }
        runCatching {
            cm.registerDefaultNetworkCallback(callback)
            netCallback = callback
        }
    }

    private fun unregisterNetworkCallback() {
        val cb = netCallback ?: return
        netCallback = null
        runCatching { getSystemService<ConnectivityManager>()?.unregisterNetworkCallback(cb) }
    }

    /**
     * Включение экрана, разблокировка и выход из режима Doze — моменты, когда пользователь ждёт связь.
     * В эти моменты сразу проверяем сессию, не дожидаясь таймера.
     */
    private fun registerWakeReceiver() {
        unregisterWakeReceiver()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                // Вход в Doze — не повод просыпаться; выход из него — повод.
                if (intent?.action == PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED &&
                    this@DostupVpnService.getSystemService<PowerManager>()?.isDeviceIdleMode == true
                ) return
                // Только действия пользователя (экран, разблокировка) — повод перезапускать ядро после сна.
                // Выход из Doze посреди ночи (окно обслуживания) — лишь проверка сессии.
                if (intent?.action == Intent.ACTION_SCREEN_ON || intent?.action == Intent.ACTION_USER_PRESENT) {
                    screenWake = true
                }
                wake.trySend(Unit)
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
        }
        runCatching {
            ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            wakeReceiver = receiver
        }
    }

    private fun unregisterWakeReceiver() {
        val receiver = wakeReceiver ?: return
        wakeReceiver = null
        runCatching { unregisterReceiver(receiver) }
    }

    /** Журнал ядра при многодневной работе не должен расти бесконечно. */
    private fun trimCoreLog() {
        runCatching {
            val f = XrayConfig.logFile(this)
            if (f.length() > MAX_CORE_LOG_BYTES) RandomAccessFile(f, "rw").use { it.setLength(0) }
        }
    }

    override fun onRevoke() {
        stopVpn()
        stopSelf()
    }

    override fun onDestroy() {
        teardown()
        running = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? = super.onBind(intent)

    private fun updateNotification(connectedAt: Long, reconnecting: Boolean) {
        getSystemService<NotificationManager>()?.notify(NOTIFICATION_ID, buildNotification(connectedAt, reconnecting))
    }

    private fun buildNotification(connectedAt: Long = 0L, reconnecting: Boolean = false): Notification {
        val nm: NotificationManager? = getSystemService()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm?.createNotificationChannel(
                NotificationChannel(NOTIFICATION_CHANNEL, "VPN", NotificationManager.IMPORTANCE_LOW),
            )
        }
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        // Кнопка в шторке: то же, что «отключить» в приложении (служба уже работает, поэтому
        // запуск из уведомления разрешён системой даже в фоне).
        val stop = PendingIntent.getService(
            this, 1, Intent(this, DostupVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = when {
            reconnecting -> "Восстановление соединения…"
            connectedAt > 0L -> "VPN включён"
            else -> "Подключение…"
        }
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL)
            .setContentTitle("DostupVPN")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_vpn_notification)
            .setContentIntent(openApp)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .addAction(0, "Отключить", stop)
            .apply {
                if (connectedAt > 0L && !reconnecting) {
                    setShowWhen(true)
                    setWhen(connectedAt)
                    setUsesChronometer(true)
                }
            }
            .build()
    }
}
