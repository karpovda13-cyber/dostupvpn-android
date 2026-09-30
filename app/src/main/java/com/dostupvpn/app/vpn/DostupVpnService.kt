package com.dostupvpn.app.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
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

        /** После неудачного heartbeat повторяем быстрее обычного — сеть могла просто ещё не проснуться. */
        private const val HEARTBEAT_RETRY_MS = 15_000L
        private const val RENEW_BACKOFF_STEP_MS = 5_000L
        private const val RENEW_BACKOFF_MAX_MS = 60_000L
        private const val MAX_CORE_LOG_BYTES = 1_000_000L
    }

    private var controller: CoreController? = null
    private var tun: ParcelFileDescriptor? = null
    private var heartbeatJob: Job? = null
    private var heartbeatSec = 180
    private var sessionStartedAt = 0L
    private var netCallback: ConnectivityManager.NetworkCallback? = null

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

            val pfd = Builder()
                .setSession("DostupVPN")
                .setMtu(XrayConfig.TUN_MTU)
                .addAddress("172.19.0.1", 30)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("1.1.1.1")
                .addDnsServer("8.8.8.8")
                .addDisallowedApplication(packageName)
                .apply { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) setMetered(false) }
                .establish() ?: error("VPN not prepared or revoked")
            tun = pfd
            EventLog.add(this, "TUN-интерфейс создан")

            val core = Libv2ray.newCoreController(coreCallback)
            core.startLoop(config, pfd.fd)
            if (!core.isRunning) error("ядро Xray не запустилось, см. xray.log")
            controller = core
            EventLog.add(this, "Ядро Xray запущено")

            val at = if (startedAt > 0L) startedAt else System.currentTimeMillis()
            sessionStartedAt = at
            sessionStore.markConnected(at)
            updateNotification(at, reconnecting = false)
            registerNetworkCallback()
            startHeartbeat()
        } catch (e: Exception) {
            val error = Errors.vpn(e)
            Log.e(TAG, "не удалось запустить VPN: ${error.code}", e)
            fail(error)
        }
    }

    /** Останавливает ядро и закрывает TUN. Состояние UI и сервер не трогает. */
    @Synchronized
    private fun teardown() {
        heartbeatJob?.cancel()
        heartbeatJob = null
        unregisterNetworkCallback()
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

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = serviceScope.launch {
            var waitMs = heartbeatSec * 1000L
            while (isActive) {
                // Ждём либо таймер, либо сигнал «сеть вернулась».
                withTimeoutOrNull(waitMs) { wake.receive() }
                trimCoreLog()
                try {
                    api.heartbeat()
                    waitMs = heartbeatSec * 1000L
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
                            waitMs = HEARTBEAT_RETRY_MS
                        }
                    }
                } catch (e: Exception) {
                    // Нет сети (телефон только проснулся и т.п.): туннель не трогаем, повторим скоро.
                    Log.w(TAG, "heartbeat network error", e)
                    waitMs = HEARTBEAT_RETRY_MS
                }
            }
        }
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
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
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
