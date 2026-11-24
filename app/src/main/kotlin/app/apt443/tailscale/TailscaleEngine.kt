package app.apt443.tailscale

import android.content.Context
import app.apt443.data.settings.KeystoreSecretStore

/**
 * Tailscale is removed from this build so the app can only reach the console on the LAN.
 * The prebuilt libtailscale binary and the VPN service are gone; every start fails closed.
 */
class TailscaleEngine(
    context: Context,
    private val prefs: KeystoreSecretStore,
) {
    val consent = VpnConsentGate(context.applicationContext)

    @Suppress("UNUSED_PARAMETER")
    suspend fun ensureRunning(
        authKey: String,
        loginServer: String,
        askPermission: Boolean,
        replaceExisting: Boolean = false,
    ): Result<Unit> = Result.failure(IllegalStateException(REMOVED))

    suspend fun logout() {
        prefs.clearAllNow()
    }

    fun tunnelUp(): Boolean = false

    fun onNetworkChanged() = Unit

    companion object {
        const val REMOVED = "Tailscale is not included in this LAN-only build."
    }
}
