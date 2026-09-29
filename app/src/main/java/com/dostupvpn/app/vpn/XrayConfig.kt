package com.dostupvpn.app.vpn

import android.content.Context
import com.dostupvpn.app.BuildConfig
import com.dostupvpn.app.net.SessionInfo
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.net.URLDecoder

/**
 * Собирает JSON-конфиг для Xray-core: TUN-inbound -> VLESS+Reality outbound.
 * Весь трафик телефона идёт через VPN; никаких geo-файлов и сложной маршрутизации.
 */
object XrayConfig {

    const val TUN_MTU = 1500

    /**
     * Российские домены: идут напрямую. Список geosite-кодов должен совпадать с GEOSITE_KEEP
     * в tools/trim_geo.py — в урезанном geosite.dat других кодов нет, и Xray на них не запустится.
     */
    private val RU_DOMAINS = listOf(
        "geosite:2gis",
        "geosite:avito",
        "geosite:category-bank-ru",
        "geosite:category-ecommerce-ru",
        "geosite:category-education-ru",
        "geosite:category-entertainment-ru",
        "geosite:category-gov-ru",
        "geosite:category-media-ru",
        "geosite:category-medicine-ru",
        "geosite:category-retail-ru",
        "geosite:category-ru",
        "geosite:category-travel-ru",
        "geosite:dzen",
        "geosite:kinopoisk",
        "geosite:mailru",
        "geosite:megafon",
        "geosite:mts-ru",
        "geosite:ok",
        "geosite:ozon",
        "geosite:rutube",
        "geosite:sber",
        "geosite:t2-ru",
        "geosite:tbank-ru",
        "geosite:tld-ru",
        "geosite:vk",
        "geosite:wildberries",
        "geosite:yandex",
        "domain:ru",
        "domain:su",
        "domain:xn--p1ai", // .рф
    )

    /** Итоговые параметры подключения (то, что реально попадает в конфиг). */
    private data class Params(
        val host: String,
        val port: Int,
        val uuid: String,
        val flow: String,
        val sni: String,
        val pbk: String,
        val sid: String,
        val fp: String,
        val spx: String,
    )

    fun logFile(context: Context) = File(context.filesDir, "xray.log")

    fun build(context: Context, session: SessionInfo): String {
        val (params, diffs) = resolve(session)
        GeoAssets.ensure(context)
        val rules = AdminRulesStore.load(context)
        writeDebug(context, params, diffs, rules)

        val log = logFile(context).also { runCatching { it.delete() } }

        val user = JSONObject()
            .put("id", params.uuid)
            .put("encryption", "none")
        if (params.flow.isNotBlank()) user.put("flow", params.flow)

        val reality = JSONObject()
            .put("serverName", params.sni)
            .put("fingerprint", params.fp)
            .put("publicKey", params.pbk)
            .put("shortId", params.sid)
        if (params.spx.isNotBlank()) reality.put("spiderX", params.spx)

        val proxy = JSONObject()
            .put("tag", "proxy")
            .put("protocol", "vless")
            .put(
                "settings",
                JSONObject().put(
                    "vnext",
                    JSONArray().put(
                        JSONObject()
                            .put("address", params.host)
                            .put("port", params.port)
                            .put("users", JSONArray().put(user)),
                    ),
                ),
            )
            .put(
                "streamSettings",
                JSONObject()
                    .put("network", "tcp")
                    .put("security", "reality")
                    .put("realitySettings", reality),
            )

        val sniffing = JSONObject()
            .put("enabled", true)
            .put("destOverride", JSONArray().put("http").put("tls").put("quic"))
            // Домен из SNI/Host используется только для выбора маршрута, а соединение идёт на
            // исходный IP. Иначе правило geoip:ru не увидело бы адрес назначения.
            .put("routeOnly", true)

        val tun = JSONObject()
            .put("tag", "tun")
            .put("protocol", "tun")
            .put("settings", JSONObject().put("name", "xray0").put("MTU", TUN_MTU))
            .put("sniffing", sniffing)

        val root = JSONObject()
            .put(
                "log",
                JSONObject()
                    .put("loglevel", if (BuildConfig.DEBUG) "info" else "warning")
                    .put("access", "none")
                    .put("error", log.path),
            )
            .put("inbounds", JSONArray().put(tun))
            .put(
                "outbounds",
                JSONArray()
                    .put(proxy)
                    .put(JSONObject().put("tag", "direct").put("protocol", "freedom"))
                    .put(JSONObject().put("tag", "block").put("protocol", "blackhole")),
            )
            .put("routing", routing(rules))
        return root.toString()
    }

    /**
     * Порядок важен (побеждает первое совпавшее правило):
     *  1) правила администратора «через VPN» — перекрывают российские списки;
     *  2) правила администратора «напрямую»;
     *  3) локальные сети — напрямую;
     *  4) российские домены и IP — напрямую;
     *  всё остальное — в VPN (первый outbound, "proxy").
     */
    private fun routing(rules: AdminRules): JSONObject {
        fun rule(key: String, values: List<String>, outbound: String): JSONObject? =
            if (values.isEmpty()) null else JSONObject()
                .put("type", "field")
                .put(key, JSONArray(values))
                .put("outboundTag", outbound)

        val list = listOfNotNull(
            rule("domain", rules.proxyDomains, "proxy"),
            rule("ip", rules.proxyIps, "proxy"),
            rule("domain", rules.directDomains, "direct"),
            rule("ip", rules.directIps, "direct"),
            rule("ip", listOf("geoip:private"), "direct"),
            rule("domain", RU_DOMAINS, "direct"),
            rule("ip", listOf("geoip:ru"), "direct"),
        )
        return JSONObject()
            .put("domainStrategy", "AsIs")
            .put("rules", JSONArray(list))
    }

    /**
     * Ссылка vless:// от сервера — эталон. Если поля JSON расходятся со ссылкой,
     * берём значения из ссылки (и записываем расхождения в session-debug.txt).
     */
    private fun resolve(s: SessionInfo): Pair<Params, List<String>> {
        val diffs = mutableListOf<String>()
        var p = Params(s.host, s.port, s.uuid, s.flow, s.sni, s.publicKey, s.shortId, s.fingerprint, "")

        val uri = runCatching { URI(s.link.trim()) }.getOrNull()
        if (uri == null || uri.scheme != "vless") {
            diffs += "link: не разобрана или не vless://"
            return p to diffs
        }
        val q = (uri.rawQuery ?: "").split('&').filter { it.contains('=') }.associate {
            val kv = it.split('=', limit = 2)
            kv[0] to runCatching { URLDecoder.decode(kv[1], "UTF-8") }.getOrDefault(kv[1])
        }

        fun pick(name: String, cur: String, fromLink: String?): String {
            if (fromLink.isNullOrBlank() || fromLink == cur) return cur
            diffs += "$name: json='$cur' link='$fromLink' -> берём из ссылки"
            return fromLink
        }

        val linkPort = uri.port.takeIf { it > 0 }
        if (linkPort != null && linkPort != p.port) diffs += "port: json=${p.port} link=$linkPort -> берём из ссылки"

        p = p.copy(
            host = pick("host", p.host, uri.host?.trim('[', ']')),
            port = linkPort ?: p.port,
            uuid = pick("uuid", p.uuid, uri.userInfo),
            flow = pick("flow", p.flow, q["flow"]),
            sni = pick("sni", p.sni, q["sni"]),
            pbk = pick("pbk", p.pbk, q["pbk"]),
            sid = pick("sid", p.sid, q["sid"]),
            fp = pick("fp", p.fp, q["fp"]),
            spx = q["spx"].orEmpty(),
        )
        return p to diffs
    }

    private fun mask(v: String) = if (v.length <= 8) v else v.take(8) + "…(len=${v.length})"

    /** Параметры без секретов целиком — для сверки с рабочей ссылкой. */
    private fun writeDebug(context: Context, p: Params, diffs: List<String>, rules: AdminRules) {
        runCatching {
            File(context.filesDir, "session-debug.txt").writeText(
                buildString {
                    appendLine("server   = ${p.host}:${p.port}")
                    appendLine("sni      = ${p.sni}")
                    appendLine("fp       = ${p.fp}")
                    appendLine("flow     = ${p.flow}")
                    appendLine("short_id = '${p.sid}' (len=${p.sid.length})")
                    appendLine("spx      = '${p.spx}'")
                    appendLine("pbk      = ${mask(p.pbk)}")
                    appendLine("uuid     = ${mask(p.uuid)}")
                    appendLine(
                        "rules    = v${rules.version}: proxy_domains=${rules.proxyDomains.size} proxy_ips=${rules.proxyIps.size} " +
                            "direct_domains=${rules.directDomains.size} direct_ips=${rules.directIps.size}",
                    )
                    appendLine("--- расхождения json/link ---")
                    if (diffs.isEmpty()) appendLine("нет") else diffs.forEach { appendLine(it) }
                },
            )
        }
    }
}
