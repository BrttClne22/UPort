package app.apt443.net

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class LanOnlyTest {
    private fun ip(text: String) = InetAddress.getByName(text)

    @Test
    fun privateAddressesAreAllowed() {
        listOf("10.10.40.1", "192.168.1.5", "172.16.0.9", "127.0.0.1", "169.254.3.4", "fd12:3456::1", "fe80::1")
            .forEach { assertTrue(it, LanOnly.isPrivate(ip(it))) }
    }

    @Test
    fun publicAddressesAreBlocked() {
        listOf("8.8.8.8", "142.250.80.46", "100.64.0.1", "2606:4700::1111")
            .forEach { assertFalse(it, LanOnly.isPrivate(ip(it))) }
    }
}
