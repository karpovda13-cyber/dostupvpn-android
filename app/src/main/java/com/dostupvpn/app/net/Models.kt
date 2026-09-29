package com.dostupvpn.app.net

data class Endpoint(val url: String, val tls: String)

data class LoginResult(
    val deviceToken: String,
    val deviceId: String,
    val endpoints: List<Endpoint>,
)

data class Me(
    val deviceName: String,
    val active: Boolean,
    val expireDate: String?,
    val daysLeft: Int,
    val sessionActive: Boolean,
    val botUrl: String,
    val heartbeatSec: Int,
    val endpoints: List<Endpoint>,
)

/** Ответ /v1/session/start: поля для сборки конфига Xray (см. vpn/XrayConfig.kt).
 *  Ссылка vless:// используется как эталон: при расхождении с полями берутся значения из неё. */
data class SessionInfo(
    val link: String,
    val host: String,
    val port: Int,
    val uuid: String,
    val flow: String,
    val sni: String,
    val publicKey: String,
    val shortId: String,
    val fingerprint: String,
    val expireDate: String?,
    val heartbeatSec: Int,
    val maxAgeSec: Int,
)

class ApiException(
    val status: Int,
    val code: String,
    override val message: String,
    val botUrl: String? = null,
) : Exception(message)
