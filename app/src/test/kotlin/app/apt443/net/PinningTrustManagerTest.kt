package app.apt443.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.PublicKey
import java.security.cert.CertificateException
import java.security.cert.X509Certificate

class PinningTrustManagerTest {
    private class MemoryPins : PinStore {
        val map = mutableMapOf<String, String>()
        override fun get(host: String) = map[host]
        override fun put(host: String, pin: String) { map[host] = pin }
        override fun clear(host: String) { map.remove(host) }
    }

    private fun certWith(key: PublicKey): X509Certificate = FakeCert(key)

    private class FakeCert(private val key: PublicKey) : X509Certificate() {
        override fun getPublicKey() = key
        override fun checkValidity() = Unit
        override fun checkValidity(date: java.util.Date?) = Unit
        override fun getVersion() = 3
        override fun getSerialNumber() = java.math.BigInteger.ONE
        override fun getIssuerDN() = null
        override fun getSubjectDN() = null
        override fun getNotBefore() = java.util.Date(0)
        override fun getNotAfter() = java.util.Date(Long.MAX_VALUE)
        override fun getTBSCertificate() = ByteArray(0)
        override fun getSignature() = ByteArray(0)
        override fun getSigAlgName() = "none"
        override fun getSigAlgOID() = "0"
        override fun getSigAlgParams() = null
        override fun getIssuerUniqueID() = null
        override fun getSubjectUniqueID() = null
        override fun getKeyUsage() = null
        override fun getBasicConstraints() = -1
        override fun getEncoded() = ByteArray(0)
        override fun verify(key: PublicKey?) = Unit
        override fun verify(key: PublicKey?, sigProvider: String?) = Unit
        override fun toString() = "fake"
        override fun hasUnsupportedCriticalExtension() = false
        override fun getCriticalExtensionOIDs() = null
        override fun getNonCriticalExtensionOIDs() = null
        override fun getExtensionValue(oid: String?) = null
    }

    private fun newKey() = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair().public

    @Test
    fun firstConnectPinsThenSameKeyPasses() {
        val pins = MemoryPins()
        val cert = certWith(newKey())
        val tm = PinningTrustManager("10.10.40.1", pins)
        tm.checkServerTrusted(arrayOf(cert), "ECDHE_ECDSA")
        assertEquals(PinningTrustManager.spkiSha256(cert), pins.map["10.10.40.1"])
        tm.checkServerTrusted(arrayOf(cert), "ECDHE_ECDSA")
    }

    @Test
    fun differentKeyIsRejected() {
        val pins = MemoryPins()
        val tm = PinningTrustManager("10.10.40.1", pins)
        tm.checkServerTrusted(arrayOf(certWith(newKey())), "ECDHE_ECDSA")
        assertThrows(CertificateException::class.java) {
            tm.checkServerTrusted(arrayOf(certWith(newKey())), "ECDHE_ECDSA")
        }
    }

    @Test
    fun emptyChainIsRejected() {
        assertThrows(CertificateException::class.java) {
            PinningTrustManager("h", MemoryPins()).checkServerTrusted(arrayOf(), "RSA")
        }
    }
}
