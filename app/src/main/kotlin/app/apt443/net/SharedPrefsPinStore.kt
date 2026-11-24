package app.apt443.net

import android.content.Context

/** Pins are public-key hashes, not secrets, so plain app-private preferences are enough. */
class SharedPrefsPinStore(context: Context) : PinStore {
    private val prefs = context.applicationContext.getSharedPreferences("console_pins", Context.MODE_PRIVATE)

    override fun get(host: String): String? = prefs.getString(host, null)

    override fun put(host: String, pin: String) {
        prefs.edit().putString(host, pin).commit()
    }

    override fun clear(host: String) {
        prefs.edit().remove(host).commit()
    }
}
