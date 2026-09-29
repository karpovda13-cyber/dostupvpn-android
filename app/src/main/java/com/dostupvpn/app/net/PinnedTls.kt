package com.dostupvpn.app.net

import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Base64
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Проверка сертификата сервера по отпечатку открытого ключа (SPKI SHA-256, base64) —
 * тот же формат, что выдают команды из DEPLOY_HTTPS.md.
 *
 * Имя и IP в сертификате НЕ проверяются (доверие даёт ключ), поэтому переезд сервера
 * на другой IP не ломает подключение. Срок действия сертификата тоже не проверяется:
 * при неверных часах телефона иначе не удалось бы войти.
 * Этот файл намеренно не зависит от Android и сторонних библиотек — его можно
 * тестировать на обычной JVM.
 */
object PinnedTls {

    fun spkiPin(cert: X509Certificate): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(cert.publicKey.encoded)
        return Base64.getEncoder().encodeToString(digest)
    }

    class PinTrustManager(private val pins: Set<String>) : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
            throw CertificateException("client auth not supported")
        }

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
            if (chain.isEmpty()) throw CertificateException("empty certificate chain")
            val pin = spkiPin(chain[0])
            if (pin !in pins) throw CertificateException("certificate pin mismatch: $pin")
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    class Bundle(val socketFactory: SSLSocketFactory, val trustManager: X509TrustManager)

    fun bundle(pins: Set<String>): Bundle {
        val tm = PinTrustManager(pins)
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, arrayOf<TrustManager>(tm), SecureRandom())
        return Bundle(ctx.socketFactory, tm)
    }
}
