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

            val core = Libv2ray.newCoreController(coreCallback)
            core.startLoop(config, pfd.fd)
            if (!core.isRunning) error("ядро Xray не запустилось, см. xray.log")
            controller = core

            sessionStore.markConnected()
            startHeartbeat()
        } catch (e: Exception) {
            val message = vpnErrorMessage(e)
            Log.e(TAG, "не удалось запустить VPN: $message", e)
            fail(message)
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
    private fun fail(message: String) {
        teardown()
        sessionStore.markFailed(message)
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
                            renewSession()
                            return@launch
                        }
                        401, 402 -> {
                            fail(apiErrorMessage(e))
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
            fail(vpnErrorMessage(e))
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

    private fun buildNotification(): android.app.Notification {
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
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL)
            .setContentTitle("DostupVPN")
            .setContentText("VPN включён")
            .setSmallIcon(R.drawable.ic_vpn_notification)
            .setContentIntent(openApp)
            .setOngoing(true)
            .build()
    }

    private fun vpnErrorMessage(e: Exception): String = when {
        e.message?.contains("ACCESS_NETWORK_STATE", ignoreCase = true) == true ->
            "VPN не запустился: отсутствует разрешение на состояние сети."
        e.message?.contains("missing VPN permission", ignoreCase = true) == true ->
            "Android не выдал разрешение на VPN. Повторите авторизацию VPN."
        e.message?.contains("VPN not prepared or revoked", ignoreCase = true) == true ->
            "Разрешение Android на VPN было отозвано."
        else -> {
            val detail = e.message?.trim()?.takeIf { it.isNotEmpty() }
            if (detail != null) "VPN не запустился: $detail" else "VPN не удалось запустить. Повторите подключение."
        }
    }

    private fun apiErrorMessage(e: ApiException): String = when (e.status) {
        402 -> "Подписка не активна. Продлите её в боте."
        401 -> "Сессия завершена. Войдите снова."
        else -> e.message.ifBlank { "Ошибка сервера (${e.status})" }
    }
}
