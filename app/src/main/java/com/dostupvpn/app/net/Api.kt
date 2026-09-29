package com.dostupvpn.app.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.dostupvpn.app.ApiConfig
import com.dostupvpn.app.data.SecureStore
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Dns
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

private fun JSONObject.strOrNull(name: String): String? = if (isNull(name)) null else getString(name)

/**
 * Клиент API. Все методы блокирующие — вызывать из Dispatchers.IO.
 * Адреса берутся из списка, присланного сервером, плюс адрес по умолчанию из ApiConfig.
 * Сетевая ошибка (в том числе несовпадение отпечатка) — пробуем следующий адрес;
 * любой HTTP-ответ сервера считается ответом и дальше по списку не идём.
 */
class ApiClient(private val store: SecureStore, context: Context) {

    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    private val plain: OkHttpClient = buildClient(null, pinnedTls = false)
    private val pinned: OkHttpClient = buildClient(null, pinnedTls = true)

    /**
     * When the VPN TUN is active, ordinary app sockets are routed into the tunnel.
     * The control API must stay reachable through the physical Wi-Fi/cellular network:
     * otherwise /v1/me and /v1/session/heartbeat depend on the VLESS tunnel they are
     * supposed to supervise. Bind OkHttp to a non-VPN Network when one is available.
     */
    private fun physicalNetwork(): Network? {
        val cm = connectivity ?: return null
        return cm.allNetworks
            .asSequence()
            .mapNotNull { n -> cm.getNetworkCapabilities(n)?.let { caps -> n to caps } }
            .filter { (_, caps) ->
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            }
            .sortedBy { (_, caps) ->
                when {
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> 0
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> 1
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> 2
                    else -> 3
                }
            }
            .map { it.first }
            .firstOrNull()
    }

    private fun buildClient(network: Network?, pinnedTls: Boolean): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
        if (network != null) {
            builder.socketFactory(network.socketFactory)
            builder.dns(object : Dns {
                override fun lookup(hostname: String): List<java.net.InetAddress> {
                    try {
                        return network.getAllByName(hostname).toList()
                    } catch (e: Exception) {
                        throw UnknownHostException("$hostname via physical network: ${e.message}")
                    }
                }
            })
        }
        if (pinnedTls) {
            val b = PinnedTls.bundle(ApiConfig.PINS)
            builder.sslSocketFactory(b.socketFactory, b.trustManager)
                .hostnameVerifier { _, _ -> true }
        }
        return builder.build()
    }

    @Volatile
    private var preferredUrl: String? = null

    private fun endpoints(): List<Endpoint> {
        val all = (store.endpoints() + Endpoint(ApiConfig.DEFAULT_API_URL, ApiConfig.DEFAULT_TLS_MODE))
            .distinctBy { it.url }
        val preferred = preferredUrl ?: return all
        return all.sortedBy { if (it.url == preferred) 0 else 1 }
    }

    private fun request(
        method: String,
        path: String,
        body: JSONObject? = null,
        auth: Boolean = true,
    ): JSONObject {
        var lastNetworkError: IOException? = null
        for (ep in endpoints()) {
            val url = (ep.url + path).toHttpUrlOrNull() ?: continue
            val builder = Request.Builder().url(url)
            if (auth) store.deviceToken()?.let { builder.header("Authorization", "Bearer $it") }
            if (method == "GET") {
                builder.get()
            } else {
                builder.post((body?.toString() ?: "").toRequestBody("application/json".toMediaType()))
            }
            // Do this for every request because the physical network can change while
            // the VPN remains enabled (Wi-Fi <-> cellular).
            val network = physicalNetwork()
            val client = if (network != null) {
                buildClient(network, pinnedTls = ep.tls != "system")
            } else if (ep.tls == "system") {
                plain
            } else {
                pinned
            }
            try {
                client.newCall(builder.build()).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    val json = try {
                        JSONObject(text)
                    } catch (_: Exception) {
                        JSONObject()
                    }
                    if (!resp.isSuccessful) {
                        throw ApiException(
                            status = resp.code,
                            code = json.optString("error", "http_${resp.code}"),
                            message = json.optString("message", ""),
                            botUrl = json.strOrNull("bot_url"),
                        )
                    }
                    preferredUrl = ep.url
                    return json
                }
            } catch (e: IOException) {
                lastNetworkError = e
            }
        }
        throw lastNetworkError ?: IOException("Нет доступных адресов API")
    }

    private fun parseEndpoints(a: JSONArray?): List<Endpoint> {
        if (a == null) return emptyList()
        val out = ArrayList<Endpoint>()
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            val url = o.optString("url", "")
            val tls = o.optString("tls", "")
            if (url.startsWith("https://") && (tls == "pinned" || tls == "system")) {
                out.add(Endpoint(url.trimEnd('/'), tls))
            }
        }
        return out
    }

    fun login(token: String, deviceName: String): LoginResult {
        val j = request(
            "POST", "/v1/login",
            JSONObject().put("token", token).put("device_name", deviceName),
            auth = false,
        )
        return LoginResult(
            deviceToken = j.getString("device_token"),
            deviceId = j.getString("device_id"),
            endpoints = parseEndpoints(j.optJSONArray("api_endpoints")),
        )
    }

    fun me(): Me {
        val j = request("GET", "/v1/me")
        val sub = j.getJSONObject("subscription")
        return Me(
            deviceName = j.optString("device_name", ""),
            active = sub.optBoolean("active", false),
            expireDate = sub.strOrNull("expire_date"),
            daysLeft = sub.optInt("days_left", 0),
            sessionActive = j.optBoolean("session_active", false),
            botUrl = j.strOrNull("bot_url") ?: ApiConfig.BOT_URL,
            heartbeatSec = j.optInt("heartbeat_interval", 180),
            endpoints = parseEndpoints(j.optJSONArray("api_endpoints")),
        )
    }

    fun logout() {
        request("POST", "/v1/logout")
    }

    // ── Методы для этапа подключения VPN (пока не используются интерфейсом) ──

    fun sessionStart(): SessionInfo {
        val j = request("POST", "/v1/session/start")
        val ep = j.getJSONObject("endpoint")
        val v = j.getJSONObject("vless")
        return SessionInfo(
            link = j.getString("link"),
            host = ep.getString("host"),
            port = ep.getInt("port"),
            uuid = v.getString("uuid"),
            flow = v.getString("flow"),
            sni = v.getString("sni"),
            publicKey = v.getString("pbk"),
            shortId = v.getString("sid"),
            fingerprint = v.getString("fp"),
            expireDate = j.strOrNull("expire_date"),
            heartbeatSec = j.optInt("heartbeat_interval", 180),
            maxAgeSec = j.optInt("max_age", 72000),
        )
    }

    fun heartbeat() {
        request("POST", "/v1/session/heartbeat")
    }

    fun sessionStop() {
        request("POST", "/v1/session/stop")
    }
}
