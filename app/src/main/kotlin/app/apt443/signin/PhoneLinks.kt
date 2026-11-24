package app.apt443.signin

import android.content.Context
import android.provider.Settings
import java.security.SecureRandom

/** LAN paths for phone QR codes: http://<tv-ip>:<port>/unifi/<tv-name>-<random token> */
object PhoneLinks {
    const val PREFIX = "/unifi"

    /**
     * The TV name plus 128 random bits. Upstream used the device name alone, which anyone on
     * the LAN could guess and use to post or pull a site file while the screen was open.
     */
    fun slug(name: String): String {
        val cleaned = name.trim().lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .take(32)
            .ifBlank { "tv" }
        return "$cleaned-${randomToken()}"
    }

    private fun randomToken(): String {
        val bytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun path(slug: String): String = "$PREFIX/$slug"

    fun url(hostPort: String, slug: String): String = "http://$hostPort${path(slug)}"

    fun matches(path: String, slug: String): Boolean {
        val base = path(slug)
        return path == base || path.startsWith("$base/")
    }
}

fun Context.tvLinkSlug(fallback: String = ""): String {
    val named = listOf(
        Settings.Global.getString(contentResolver, "device_name"),
        Settings.System.getString(contentResolver, "device_name"),
    ).firstOrNull { !it.isNullOrBlank() }.orEmpty()
    return named.ifBlank { fallback }
}
