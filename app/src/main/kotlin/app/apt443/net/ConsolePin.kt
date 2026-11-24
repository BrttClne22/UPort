package app.apt443.net

import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager

/** Where the pinned console key lives. One pin per console host. */
interface PinStore {
    fun get(host: String): String?
    fun put(host: String, pin: String)
    fun clear(host: String)
}

/**
 * Trust-on-first-use for a console with a self-signed certificate.
 * The first connection records the SHA-256 of the leaf's public key; later
 * connections must present the same key or the handshake fails. This replaces
 * the upstream trust manager that accepted any certificate.
 */
class PinningTrustManager(
    private val host: String,
    private val store: PinStore,
) : X509TrustManager {
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        throw CertificateException("Client certificates are not used.")
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val leaf = chain?.firstOrNull() ?: throw CertificateException("The console sent no certificate.")
        leaf.checkValidity()
        val seen = spkiSha256(leaf)
        val pinned = store.get(host)
        when {
            pinned == null -> store.put(host, seen)
            pinned != seen -> throw CertificateException(
                "The console's certificate changed. If you replaced or reset the console, " +
                    "clear the pinned certificate in Settings.",
            )
        }
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

    companion object {
        fun spkiSha256(cert: X509Certificate): String =
            MessageDigest.getInstance("SHA-256").digest(cert.publicKey.encoded)
                .joinToString("") { "%02x".format(it) }
    }
}
