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
import android.net.NetworkCapabilities
import android.net.NetworkRequest
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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
import java.util.concurrent.atomic.AtomicInteger

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
         * true между «пользователь нажал отключить» и уничтожением службы. Новое подключение ждёт,
         * пока старый экземпляр умрёт, иначе его onDestroy снёс бы свежий туннель.
         */
        @Volatile
        var stopPending = false
            private set

        fun notifyStopRequested() {
            if (running) stopPending = true
        }

        /** Живёт дольше любого экземпляра службы: запрос остановки сессии не должен пропасть вместе с ней. */
        private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /** Перезапуски по смене сети не чаще раза в это время; лишние откладываются, а не теряются. */
        private const val NET_RESTART_COOLDOWN_MS = 10_000L

        /** Предохранитель: столько перезапусков по смене сети за окно — и мы перестаём на них реагировать. */
        private const val NET_STORM_LIMIT = 4
        private const val NET_STORM_WINDOW_MS = 60_000L
        private const val NET_STORM_MUTE_MS = 60_000L

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

    /**
     * Номер «запуска». Увеличивается при каждом новом подключении, остановке и ошибке. Любая долгая операция
     * (heartbeat, обновление сессии, перезапуск) запоминает номер и перед побочным эффектом сверяется с текущим:
     * устаревшая операция молча умирает и не может ни воскресить туннель, ни испортить состояние.
     */
    private val generation = AtomicInteger(0)
    private fun isCurrent(gen: Int) = gen == generation.get()

    /** Когда в последний раз поднимали туннель (elapsedRealtime) и когда перезапускали из-за смены сети. */
    @Volatile
    private var lastRestartAt = 0L
    private val netRestarts = ArrayDeque<Long>()
    private var netMutedUntil = 0L

    /** Физические (не VPN) сети с интернетом и их возможности. Защищено монитором самого словаря. */
    private val physNets = LinkedHashMap<Network, NetworkCapabilities>()
    @Volatile
    private var lostAllNetworks = false
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

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sessionStore by lazy { VpnSessionStore(this) }
    private val api by lazy { ApiClient(SecureStore(applicationContext), applicationContext) }

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
        stopPending = false
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopVpn()
            return START_NOT_STICKY
        }
        val config = intent?.getStringExtra(EXTRA_CONFIG) ?: run {
            stopSelf()
            return START_NOT_STICKY
        }
        heartbeatSec = intent.getIntExtra(EXTRA_HEARTBEAT_SEC, 180).coerceAtLeast(30)
        // Новый запуск отменяет всё, что относилось к предыдущему.
        val gen = generation.incrementAndGet()
        // Запуск ядра блокирующий — не на главном потоке.
        serviceScope.launch { startTunnel(config, gen = gen) }
        return START_NOT_STICKY
    }

    /** [startedAt] — время начала подключения, если это перезапуск внутри той же сессии (таймер не сбрасывается). */
    @Synchronized
    private fun startTunnel(config: String, startedAt: Long = 0L, gen: Int) {
        if (!isCurrent(gen)) return
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
            if (!isCurrent(gen)) {            // пока строили интерфейс, пользователь отключился
                runCatching { pfd.close() }
                return
            }
            tun = pfd
            EventLog.add(this, "TUN-интерфейс создан (запуск №$gen)")

            val core = Libv2ray.newCoreController(coreCallback)
            core.startLoop(config, pfd.fd)
            if (!core.isRunning) error("ядро Xray не запустилось, см. xray.log")
            controller = core
            if (!isCurrent(gen)) {            // отключились во время старта ядра — убираем за собой и выходим
                teardown()
                return
            }
            lastConfig = config
            networkSwitched = false
            screenWake = false
            sleepBaselineMs = deepSleepMs()
            EventLog.add(this, "Ядро Xray запущено")

            val at = if (startedAt > 0L) startedAt else System.currentTimeMillis()
            sessionStartedAt = at
            sessionStore.markConnected(at)
            updateNotification(at, reconnecting = false)
            lastRestartAt = SystemClock.elapsedRealtime()
            registerNetworkCallback()
            registerWakeReceiver()
            startHeartbeat(gen)
        } catch (e: Exception) {
            if (!isCurrent(gen)) {
                teardown()
                return
            }
            val error = Errors.vpn(e)
            Log.e(TAG, "не удалось запустить VPN: ${error.code}", e)
            fail(error, gen)
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

    /**
     * Штатная остановка (кнопка «отключить» в приложении или в уведомлении). Сначала гасим номер запуска —
     * с этого момента все операции текущего запуска считаются устаревшими, — затем всё остальное уже
     * не на главном потоке: teardown ждёт блокировку, пока может идти запуск ядра.
     */
    private fun stopVpn() {
        generation.incrementAndGet()
        stopPending = true
        appScope.launch {
            teardown()
            sessionStore.clear()
            stopSelf()
            runCatching { api.sessionStop() }
        }
    }

    /** Окончательная остановка с сообщением для UI (сообщение не затирается). */
    private fun fail(error: AppError, gen: Int) {
        if (!isCurrent(gen)) return
        generation.incrementAndGet()
        EventLog.add(this, "ОШИБКА ${error.code}: ${error.title}${if (error.detail.isBlank()) "" else " | ${error.detail}"}")
        teardown()
        sessionStore.markFailed(error)
        appScope.launch { runCatching { api.sessionStop() } }
        stopSelf()
    }

    /** Время глубокого сна (CPU выключен) с начала загрузки: elapsedRealtime идёт во сне, uptimeMillis — нет. */
    private fun deepSleepMs(): Long = SystemClock.elapsedRealtime() - SystemClock.uptimeMillis()

    private fun startHeartbeat(gen: Int) {
        heartbeatJob?.cancel()
        heartbeatJob = serviceScope.launch {
            var waitMs = heartbeatSec * 1000L
            var failures = 0
            var lastAttempt = 0L   // SystemClock.elapsedRealtime(): идёт и во время сна, в отличие от таймеров корутин
            while (isActive && isCurrent(gen)) {
                // Ждём либо таймер, либо сигнал пробуждения (экран, разблокировка, выход из Doze, сеть).
                // Таймер корутин замирает, пока телефон спит, поэтому без сигналов проверка после сна
                // откладывалась бы на оставшуюся часть интервала.
                val woken = withTimeoutOrNull(waitMs) { wake.receive(); true } ?: false
                if (!isCurrent(gen)) return@launch
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
                    // Запрос мог идти, пока пользователь отключался: устаревший запуск ничего не делает.
                    if (!isCurrent(gen)) return@launch
                    when (e.status) {
                        410 -> {           // сервер удалил конфиг (долго не было heartbeat) — создаём новый
                            EventLog.add(this@DostupVpnService, "Сервер удалил сессию (410) — восстанавливаем")
                            renewSession(gen)
                            return@launch
                        }
                        401, 402 -> {
                            fail(Errors.fromApi(e), gen)
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
                if (!isCurrent(gen)) return@launch

                // Сессия на сервере жива. Теперь — не «забыли» ли соединения, пока телефон спал или менялась сеть.
                val switched = networkSwitched
                val screen = screenWake
                networkSwitched = false
                screenWake = false
                if (!switched && !screen) continue

                var netRestart = false
                if (switched) {
                    val delayMs = networkRestartDelay(SystemClock.elapsedRealtime())
                    if (delayMs > 0L) {
                        // Слишком скоро после прошлого перезапуска: откладываем, а не теряем.
                        networkSwitched = true
                        screenWake = screenWake || screen
                        waitMs = delayMs
                        continue
                    }
                    netRestart = delayMs == 0L   // отрицательное значение — идёт пауза после шторма, событие игнорируем
                }
                val current = deepSleepMs()
                val sleptMs = current - sleepBaselineMs
                sleepBaselineMs = current
                val reason = when {
                    netRestart -> "сменилась сеть"
                    sleptMs >= SOFT_RESET_AFTER_SLEEP_MS -> "телефон спал ${sleptMs / 1000} с"
                    else -> null
                }
                if (reason != null) {
                    if (netRestart) netRestarts.addLast(SystemClock.elapsedRealtime())
                    softRestart(gen, reason)
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
    private fun softRestart(gen: Int, reason: String) {
        if (!isCurrent(gen)) return
        val config = lastConfig ?: return
        EventLog.add(this, "Перезапуск туннеля: $reason")
        startTunnel(config, sessionStartedAt, gen)   // сам запустит новый heartbeat
    }

    /**
     * Можно ли сейчас перезапускать туннель из-за смены сети. 0 — можно; больше нуля — подождать столько мс;
     * меньше нуля — сработал предохранитель (слишком частые смены), событие игнорируем.
     */
    private fun networkRestartDelay(now: Long): Long {
        if (now < netMutedUntil) return -1L
        while (netRestarts.isNotEmpty() && now - netRestarts.first() > NET_STORM_WINDOW_MS) netRestarts.removeFirst()
        if (netRestarts.size >= NET_STORM_LIMIT) {
            netMutedUntil = now + NET_STORM_MUTE_MS
            netRestarts.clear()
            EventLog.add(this, "Слишком частые смены сети — игнорирую их ${NET_STORM_MUTE_MS / 1000} с")
            return -1L
        }
        val since = now - lastRestartAt
        return if (lastRestartAt != 0L && since < NET_RESTART_COOLDOWN_MS) NET_RESTART_COOLDOWN_MS - since else 0L
    }

    /**
     * Создаёт новую сессию и перезапускает ядро. Не сдаётся при сетевых ошибках: телефон мог
     * проснуться раньше, чем появилась сеть. Окончательные ошибки — только 401/402.
     */
    private suspend fun renewSession(gen: Int) {
        if (!isCurrent(gen)) return
        sessionStore.markReconnecting()
        updateNotification(sessionStartedAt, reconnecting = true)
        var waitMs = 0L
        var attempt = 0
        while (true) {
            if (waitMs > 0L) withTimeoutOrNull(waitMs) { wake.receive() }
            if (!isCurrent(gen)) return
            attempt++
            try {
                val session = api.sessionStart()
                // Пока шёл запрос, пользователь мог нажать «отключить» — не воскрешаем туннель.
                currentCoroutineContext().ensureActive()
                if (!isCurrent(gen)) return
                heartbeatSec = session.heartbeatSec.coerceAtLeast(30)
                val config = XrayConfig.build(this, session)
                EventLog.add(this, "Сессия обновлена (попытка $attempt)")
                startTunnel(config, sessionStartedAt, gen)   // сам запустит новый heartbeat
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                if (!isCurrent(gen)) return
                if (e.status == 401 || e.status == 402) {
                    fail(Errors.fromApi(e), gen)
                    return
                }
                EventLog.add(this, "Восстановление: попытка $attempt — ошибка ${e.status}")
            } catch (e: Exception) {
                EventLog.add(this, "Восстановление: попытка $attempt — нет связи")
            }
            waitMs = minOf(RENEW_BACKOFF_STEP_MS * attempt, RENEW_BACKOFF_MAX_MS)
        }
    }

    /**
     * Следим за физическими сетями (не VPN), а не за «сетью по умолчанию». На Android 11 default-колбэк
     * приложения показывает нашу же VPN-сеть: каждый новый TUN выглядел как «смена сети» и запускал
     * следующий перезапуск (цикл раз в секунду), а настоящее переключение Wi-Fi <-> LTE оставалось незамеченным.
     * Текущая сеть — лучшая из физических: проверенная (VALIDATED), затем Wi-Fi.
     */
    private fun registerNetworkCallback() {
        unregisterNetworkCallback()
        val cm: ConnectivityManager = getSystemService() ?: return
        lastNetwork = null   // первое определение лишь запоминает текущую сеть
        lostAllNetworks = false
        synchronized(physNets) { physNets.clear() }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                cm.getNetworkCapabilities(network)?.let { onPhysicalChanged(network, it, available = true) }
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                onPhysicalChanged(network, caps, available = false)
            }

            override fun onLost(network: Network) {
                synchronized(physNets) { physNets.remove(network) }
                onPhysicalChanged(null, null, available = false)
            }
        }
        runCatching {
            cm.registerNetworkCallback(request, callback)
            netCallback = callback
        }
    }

    private fun onPhysicalChanged(network: Network?, caps: NetworkCapabilities?, available: Boolean) {
        val best = synchronized(physNets) {
            if (network != null && caps != null && !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                physNets[network] = caps
            }
            physNets.entries.maxByOrNull { (_, c) ->
                (if (c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) 2 else 0) +
                    (if (c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) 1 else 0)
            }?.key
        }
        if (best == null) {
            lostAllNetworks = true     // сети нет совсем: когда она вернётся, старые соединения считаем мёртвыми
            return
        }
        val previous = lastNetwork
        if (previous == best && !lostAllNetworks) {
            if (available) wake.trySend(Unit)
            return
        }
        if (previous != null) networkSwitched = true
        lastNetwork = best
        lostAllNetworks = false
        wake.trySend(Unit)
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
    }

    override fun onDestroy() {
        generation.incrementAndGet()      // всё, что ещё крутится в этом экземпляре, теперь устарело
        serviceScope.cancel()             // и не должно жить дольше службы
        teardown()
        stopPending = false
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
