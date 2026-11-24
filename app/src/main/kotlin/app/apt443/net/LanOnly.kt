package app.apt443.net

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/**
 * Network interceptor that refuses any connection whose remote address is not on a private
 * network. It runs after DNS and connect, so it also catches hostnames that resolve to
 * public addresses and IP literals. This build never needs the internet.
 */
object LanOnly : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val address = chain.connection()?.route()?.socketAddress?.address
            ?: throw IOException("Blocked: no route information for ${chain.request().url.host}.")
        if (!isPrivate(address)) {
            throw IOException("Blocked: ${chain.request().url.host} is not on the local network.")
        }
        return chain.proceed(chain.request())
    }

    fun isPrivate(address: InetAddress): Boolean {
        if (address.isLoopbackAddress || address.isSiteLocalAddress || address.isLinkLocalAddress) return true
        return when (address) {
            // fc00::/7 unique local addresses.
            is Inet6Address -> (address.address[0].toInt() and 0xFE) == 0xFC
            is Inet4Address -> false
            else -> false
        }
    }
}
