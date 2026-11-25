package com.zapfig.app

import android.app.Application
import android.content.Context
import android.content.Intent
import com.zapfig.app.engine.EngineManager
import com.zapfig.app.ui.MainActivity

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // Carrega o motor já instalado (se houver). O primeiro download é feito ao ligar o bot.
        try {
            EngineManager.loadInstalled(this)
        } catch (e: Throwable) {
            BotState.log("Não consegui carregar o motor instalado: ${e.message}")
        }
    }

    companion object {
        /** Reinicia o app (motor novo só entra em uso num processo novo). */
        fun restart(ctx: Context, startBot: Boolean) {
            ctx.getSharedPreferences("zapfig", Context.MODE_PRIVATE).edit()
                .putBoolean("start_after_restart", startBot).commit()
            BotService.stop(ctx)
            ctx.startActivity(Intent(ctx, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            Runtime.getRuntime().exit(0)
        }
    }
}
