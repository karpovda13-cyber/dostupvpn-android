package com.dostupvpn.app.vpn

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.IDN
import java.net.InetAddress

/**
 * Дополнительные правила маршрутизации, которые вручную ведёт администратор
 * (файл /etc/vpn/app_rules.json на сервере, отдаётся через GET /v1/rules).
 *
 *  proxy_*  — всегда через VPN (перекрывают российские списки: например, заблокированный .ru-сайт);
 *  direct_* — всегда напрямую;
 *  bypass_apps — приложения, которые вообще не используют VPN: весь их трафик идёт мимо туннеля.
 *    Запись — точное имя пакета ("ru.sberbankmobile") либо маска по началу имени ("ru.*" — все
 *    установленные пакеты, имя которых начинается с "ru.").
 *    Так такие приложения не могут сравнить «какой IP видят российские сервисы» и «какой видят зарубежные»
 *    и не видят адрес выхода VPN. Список ведёт администратор на сервере.
 *
 * Значения жёстко фильтруются: одна опечатка в чужом правиле не должна ломать запуск ядра
 * у всех пользователей (Xray не стартует на неизвестном geosite:/geoip: коде).
 * Поэтому допустимы только домены (domain:/full:) и IP/CIDR.
 */
data class AdminRules(
    val version: Int = 0,
    val proxyDomains: List<String> = emptyList(),
    val proxyIps: List<String> = emptyList(),
    val directDomains: List<String> = emptyList(),
    val directIps: List<String> = emptyList(),
    val bypassApps: List<String> = emptyList(),
)

object AdminRulesStore {
    private const val FILE = "rules.json"
    private const val MAX_ITEMS = 500

    fun load(context: Context): AdminRules = runCatching {
        parse(JSONObject(File(context.filesDir, FILE).readText()))
    }.getOrDefault(AdminRules())

    /** Обрабатывает ответ /v1/rules. Сохраняет только если сервер прислал новую версию. */
    fun update(context: Context, response: JSONObject) {
        if (!response.optBoolean("changed", false)) return
        val rules = parse(response)
        if (rules.version <= 0) return
        val json = JSONObject()
            .put("version", rules.version)
            .put(
                "rules",
                JSONObject()
                    .put("proxy_domains", JSONArray(rules.proxyDomains))
                    .put("proxy_ips", JSONArray(rules.proxyIps))
                    .put("direct_domains", JSONArray(rules.directDomains))
                    .put("direct_ips", JSONArray(rules.directIps))
                    .put("bypass_apps", JSONArray(rules.bypassApps)),
            )
        File(context.filesDir, FILE).writeText(json.toString())
    }

    fun version(context: Context): Int = load(context).version

    private fun parse(root: JSONObject): AdminRules {
        val r = root.optJSONObject("rules") ?: JSONObject()
        return AdminRules(
            version = root.optInt("version", 0),
            proxyDomains = strings(r, "proxy_domains").mapNotNull(::cleanDomain).distinct().take(MAX_ITEMS),
            proxyIps = strings(r, "proxy_ips").mapNotNull(::cleanIp).distinct().take(MAX_ITEMS),
            directDomains = strings(r, "direct_domains").mapNotNull(::cleanDomain).distinct().take(MAX_ITEMS),
            directIps = strings(r, "direct_ips").mapNotNull(::cleanIp).distinct().take(MAX_ITEMS),
            bypassApps = strings(r, "bypass_apps").mapNotNull(::cleanPackage).distinctBy { it.lowercase() }.take(MAX_ITEMS),
        )
    }

    private fun strings(o: JSONObject, key: String): List<String> {
        val a = o.optJSONArray(key) ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.optString(it, "").takeIf { s -> s.isNotBlank() } }
    }

    private val LABELS = Regex("^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)*$")

    /** "example.com", ".example.com", "domain:example.com" -> суффиксное правило; "full:a.example.com" -> точное. */
    internal fun cleanDomain(raw: String): String? {
        var s = raw.trim().lowercase()
        val prefix = when {
            s.startsWith("full:") -> "full:".also { s = s.removePrefix("full:") }
            else -> "domain:".also { s = s.removePrefix("domain:") }
        }
        s = s.trim().trimStart('.')
        if (s.isEmpty() || s.length > 253) return null
        val ascii = try { IDN.toASCII(s) } catch (_: Exception) { return null }
        return if (LABELS.matches(ascii)) prefix + ascii else null
    }

    private val PACKAGE = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")

    private val PACKAGE_BASE = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)*$")

    /**
     * Имя пакета ("com.example.app") или маска по началу имени ("ru.*"). Всё остальное отбрасывается.
     * Маска обязана иметь основу не короче двух символов: так опечатка вроде "*" или "a.*" не исключит
     * из VPN почти все приложения на телефоне.
     */
    internal fun cleanPackage(raw: String): String? {
        val s = raw.trim()
        if (s.length > 255) return null
        if (s.endsWith(".*")) {
            val base = s.dropLast(2)
            return if (base.length >= 2 && PACKAGE_BASE.matches(base)) s else null
        }
        return if (PACKAGE.matches(s)) s else null
    }

    /** IPv4/IPv6 адрес или CIDR. Префикс /0 запрещён (это «весь интернет»). */
    internal fun cleanIp(raw: String): String? {
        val s = raw.trim().lowercase()
        val addr = s.substringBefore('/')
        val prefix: Int? = if ('/' in s) (s.substringAfter('/').toIntOrNull() ?: return null) else null
        val isV6 = ':' in addr
        val ok = if (isV6) {
            addr.all { it in "0123456789abcdef:." } && addr.count { it == ':' } >= 2 &&
                runCatching { InetAddress.getByName(addr) }.isSuccess
        } else {
            val p = addr.split('.')
            p.size == 4 && p.all { it.isNotEmpty() && it.length <= 3 && it.all(Char::isDigit) && it.toInt() in 0..255 }
        }
        if (!ok) return null
        if (prefix != null && prefix !in 1..(if (isV6) 128 else 32)) return null
        return s
    }
}
