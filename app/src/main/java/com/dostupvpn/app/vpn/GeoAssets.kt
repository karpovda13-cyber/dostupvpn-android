package com.dostupvpn.app.vpn

import android.content.Context
import java.io.File

/**
 * Xray читает geoip.dat / geosite.dat с диска (каталог, заданный в initCoreEnv),
 * а в APK они лежат в assets/geo. Перед каждым запуском ядра убеждаемся, что копии актуальны.
 * Файлы урезаны до российских списков (см. tools/trim_geo.py), ~450 КБ вместо 28 МБ.
 */
object GeoAssets {
    private val FILES = listOf("geoip.dat", "geosite.dat")

    fun ensure(context: Context) {
        for (name in FILES) {
            val bytes = context.assets.open("geo/$name").use { it.readBytes() }
            val target = File(context.filesDir, name)
            if (target.isFile && target.length() == bytes.size.toLong() && target.readBytes().contentEquals(bytes)) continue
            val tmp = File(context.filesDir, "$name.tmp")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(target)) {
                target.delete()
                check(tmp.renameTo(target)) { "не удалось обновить $name" }
            }
        }
    }
}
