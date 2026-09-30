package com.dostupvpn.app.vpn

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray

/**
 * VPN-сервис: поднимает системный TUN и отдаёт его дескриптор Xray-core
 * (inbound "tun" в конфиге). Свой пакет исключён из VPN (addDisallowedApplication),
 * поэтому исходящие сокеты Xray и запросы к API идут напрямую, без петли в туннель.
 */
class DostupVpnService : VpnService() {

    companion object {
        private const val TAG = "DostupVpnService"
        private const val NOTIFICATION_CHANNEL = "vpn"
        private const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "com.dostupvpn.app.STOP"
        const val EXTRA_CONFIG = "config"
        const val EXTRA_HEARTBEAT_SEC = "heartbeat_sec"
    }

    private var controller: CoreController? = null
    private var tun: ParcelFileDescriptor? = null
    private var heartbeatJob: Job? = null
    private var heartbeatSec = 180
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

    @Synchronized
    private fun startTunnel(config: String) {
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

            val connectedAt = System.currentTimeMillis()
            sessionStore.markConnected(connectedAt)
            // Обновляем уведомление: живой таймер подключения и кнопка «Отключить».
            getSystemService<NotificationManager>()?.notify(NOTIFICATION_ID, buildNotification(connectedAt))
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
        controller?.let { runCatching { it.stopLoop() } }
        controller = null
        // Android-версия TUN в Xray дескриптор не закрывает — владелец только мы.
        tun?.let { runCatching { it.close() } }
        tun = null
    }

    /** Штатная остановка (кнопка «отключить»). */
    private fun stopVpn() {
        teardown()
        sessionStore.clear()
        serviceScope.launch { runCatching { api.sessionStop() } }
    }

    /** Аварийная остановка с сообщением для UI (сообщение не затирается). */
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
            while (isActive) {
                delay(heartbeatSec * 1000L)
                try {
                    api.heartbeat()
                } catch (e: ApiException) {
                    when (e.status) {
                        410 -> {           // сервер удалил конфиг — создаём новый и перезапускаем ядро
                            EventLog.add(this@DostupVpnService, "Сервер удалил сессию (410) — создаём новую")
                            renewSession()
                            return@launch
                        }
                        401, 402 -> {
                            fail(Errors.fromApi(e))
                            return@launch
                        }
                        else -> Log.w(TAG, "heartbeat error: ${e.message}")
                    }
                } catch (e: Exception) {
                    // Временная потеря сети: туннель не трогаем, повторим на следующем интервале.
                    Log.w(TAG, "heartbeat network error", e)
                }
            }
        }
    }

    private fun renewSession() {
        val config = try {
            val session = api.sessionStart()
            heartbeatSec = session.heartbeatSec.coerceAtLeast(30)
            XrayConfig.build(this, session)
        } catch (e: Exception) {
            Log.e(TAG, "не удалось обновить VPN-сессию", e)
            fail(Errors.from(e))
            return
        }
        startTunnel(config)   // сам создаст новый heartbeat
    }

    override fun onRevoke() {
        stopVpn()
        stopSelf()
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? = super.onBind(intent)

    private fun buildNotification(connectedAt: Long = 0L): android.app.Notification {
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
        // Кнопка в шторке: то же самое, что «отключить» в приложении (служба уже работает, поэтому
        // запуск из уведомления разрешён системой даже в фоне).
        val stop = PendingIntent.getService(
            this, 1, Intent(this, DostupVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL)
            .setContentTitle("DostupVPN")
            .setContentText(if (connectedAt > 0L) "VPN включён" else "Подключение…")
            .setSmallIcon(R.drawable.ic_vpn_notification)
            .setContentIntent(openApp)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .addAction(0, "Отключить", stop)
            .apply {
                if (connectedAt > 0L) {
                    setShowWhen(true)
                    setWhen(connectedAt)
                    setUsesChronometer(true)
                }
            }
            .build()
    }
}
