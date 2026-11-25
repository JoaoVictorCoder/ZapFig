package com.zapfig.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Religa o bot depois de reiniciar o celular ou atualizar o app (se o usuário quiser). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val prefs = Prefs(ctx)
        val relevant = intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED
        if (relevant && prefs.acceptedTerms && prefs.startOnBoot && prefs.wantRunning) {
            try {
                BotService.start(ctx, prefs.phone)
            } catch (e: Exception) {
                // Android pode bloquear o início em segundo plano em alguns aparelhos.
            }
        }
    }
}
