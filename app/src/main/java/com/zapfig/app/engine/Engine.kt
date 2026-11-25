package com.zapfig.app.engine

import com.zapfig.engine.bot.Bot
import com.zapfig.engine.bot.Callback

/**
 * Único ponto do app que fala com o motor. Encostar na classe Bot antes de o
 * motor estar carregado derruba o app (a biblioteca nativa não existe ainda),
 * então todas as chamadas passam por aqui.
 */
object Engine {
    val loaded get() = EngineManager.isLoaded

    fun version(): String? = if (loaded) Bot.engineVersion() else null

    fun configure(pack: String, author: String, autoImage: Boolean, autoGif: Boolean, maxSeconds: Int) {
        if (loaded) Bot.configure(pack, author, autoImage, autoGif, maxSeconds.toLong())
    }

    fun start(dataDir: String, phone: String, cb: Callback) {
        check(loaded) { "o bot ainda não foi baixado" }
        Bot.start(dataDir, phone, cb)
    }

    fun stop() { if (loaded) Bot.stop() }

    fun isRunning(): Boolean = loaded && Bot.isRunning()

    fun pairedNumber(dataDir: String): String = if (loaded) Bot.pairedNumber(dataDir) else ""

    fun logout(dataDir: String) {
        if (loaded) Bot.logout(dataDir)
    }
}
