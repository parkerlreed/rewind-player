package dev.parker.rewind.engine

import android.content.Context
import android.system.Os
import android.util.Base64
import android.util.Log
import java.io.File
import java.security.KeyStore
import java.security.cert.X509Certificate

/**
 * libtorrent4j statically links OpenSSL with OPENSSLDIR pointing at its CI machine, so on a device
 * it trusts nothing and every HTTPS web seed / tracker fails verification. Android's CA store isn't
 * in a format OpenSSL can read directly (old-style hash names), so export it as a PEM bundle and
 * point OpenSSL at it via SSL_CERT_FILE. Must run before the libtorrent session starts.
 */
object CaBundle {
    private const val TAG = "CaBundle"

    fun install(context: Context) {
        try {
            val store = KeyStore.getInstance("AndroidCAStore").apply { load(null) }
            val pem = buildString {
                // Includes system roots and any user-installed CAs.
                for (alias in store.aliases()) {
                    val cert = store.getCertificate(alias) as? X509Certificate ?: continue
                    append("-----BEGIN CERTIFICATE-----\n")
                    Base64.encodeToString(cert.encoded, Base64.NO_WRAP).chunked(64).forEach { append(it).append('\n') }
                    append("-----END CERTIFICATE-----\n")
                }
            }
            val file = File(context.filesDir, "cacerts.pem")
            val tmp = File(context.filesDir, "cacerts.pem.tmp")
            tmp.writeText(pem)
            tmp.renameTo(file)
            Os.setenv("SSL_CERT_FILE", file.absolutePath, true)
            Log.i(TAG, "Exported ${store.size()} CA certificates for libtorrent")
        } catch (e: Exception) {
            Log.e(TAG, "Couldn't export CA certificates; HTTPS web seeds and trackers will fail", e)
        }
    }
}
