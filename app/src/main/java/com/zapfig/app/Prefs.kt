package com.zapfig.app

import android.content.Context
import com.zapfig.app.media.CropMode

/** Ajustes salvos do app. */
class Prefs(ctx: Context) {
    private val sp = ctx.applicationContext.getSharedPreferences("zapfig", Context.MODE_PRIVATE)

    var acceptedTerms: Boolean
        get() = sp.getBoolean("accepted_terms", false)
        set(v) = sp.edit().putBoolean("accepted_terms", v).apply()

    var phone: String
        get() = sp.getString("phone", "") ?: ""
        set(v) = sp.edit().putString("phone", v).apply()

    var pack: String
        get() = sp.getString("pack", "ZapFig") ?: "ZapFig"
        set(v) = sp.edit().putString("pack", v).apply()

    var author: String
        get() = sp.getString("author", "") ?: ""
        set(v) = sp.edit().putString("author", v).apply()

    var autoImage: Boolean
        get() = sp.getBoolean("auto_image", true)
        set(v) = sp.edit().putBoolean("auto_image", v).apply()

    var autoGif: Boolean
        get() = sp.getBoolean("auto_gif", true)
        set(v) = sp.edit().putBoolean("auto_gif", v).apply()

    var crop: CropMode
        get() = runCatching { CropMode.valueOf(sp.getString("crop", "FIT")!!) }.getOrDefault(CropMode.FIT)
        set(v) = sp.edit().putString("crop", v.name).apply()

    var maxSeconds: Int
        get() = sp.getInt("max_seconds", 6)
        set(v) = sp.edit().putInt("max_seconds", v.coerceIn(1, 8)).apply()

    var fps: Int
        get() = sp.getInt("fps", 10)
        set(v) = sp.edit().putInt("fps", v.coerceIn(5, 15)).apply()

    var startOnBoot: Boolean
        get() = sp.getBoolean("start_on_boot", false)
        set(v) = sp.edit().putBoolean("start_on_boot", v).apply()

    /** O usuário deixou o bot ligado (para religar após reboot/atualização). */
    var wantRunning: Boolean
        get() = sp.getBoolean("want_running", false)
        set(v) = sp.edit().putBoolean("want_running", v).apply()

    var totalStickers: Int
        get() = sp.getInt("stat_total", 0)
        set(v) = sp.edit().putInt("stat_total", v).apply()

    var animatedStickers: Int
        get() = sp.getInt("stat_animated", 0)
        set(v) = sp.edit().putInt("stat_animated", v).apply()
}
