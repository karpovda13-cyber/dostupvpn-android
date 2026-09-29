package com.dostupvpn.app

import android.app.Application
import android.util.Log
import libv2ray.Libv2ray
import java.io.File

/** Должна быть указана в AndroidManifest.xml как android:name=".DostupApplication". */
class DostupApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        installCrashLogger()
        // Путь для ресурсов ядра. Geo-файлы мы не используем (в конфиге нет geoip/geosite-правил).
        runCatching { Libv2ray.initCoreEnv(filesDir.path, "") }
            .onFailure { Log.e("DostupApplication", "initCoreEnv failed", it) }
    }

    /** Java-исключения пишем в файл, чтобы после вылета было что показать. */
    private fun installCrashLogger() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching {
                File(filesDir, "java-crash.log").writeText(
                    "thread=${thread.name}\n" + Log.getStackTraceString(e),
                )
            }
            previous?.uncaughtException(thread, e)
        }
    }

    companion object {
        /** Возвращает текст предыдущего аварийного завершения (если был) и удаляет файл. */
        fun takeCrashReport(app: Application): String? {
            val f = File(app.filesDir, "java-crash.log")
            if (!f.isFile || f.length() == 0L) return null
            val text = f.readText().trim().takeLast(1200)
            runCatching { f.delete() }
            return text.ifBlank { null }
        }
    }
}
