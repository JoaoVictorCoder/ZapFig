package com.zapfig.app

import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.zapfig.app.ui.MainActivity

/** Botão nas Configurações rápidas (puxar a barra de notificação) para ligar/desligar. */
class ToggleTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        render()
    }

    override fun onClick() {
        super.onClick()
        val prefs = Prefs(this)
        if (!prefs.acceptedTerms || (!BotState.ui.value.paired && prefs.phone.isBlank())) {
            // Primeira vez: precisa abrir o app.
            val i = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (Build.VERSION.SDK_INT >= 34) {
                startActivityAndCollapse(android.app.PendingIntent.getActivity(this, 0, i,
                    android.app.PendingIntent.FLAG_IMMUTABLE))
            } else {
                @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
                startActivityAndCollapse(i)
            }
            return
        }
        if (BotState.running) BotService.stop(this) else BotService.start(this, prefs.phone)
        render(!BotState.running)
    }

    private fun render(on: Boolean = BotState.running) {
        val t = qsTile ?: return
        t.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        t.label = "ZapFig"
        if (Build.VERSION.SDK_INT >= 29) t.subtitle = if (on) "Ligado" else "Desligado"
        t.icon = Icon.createWithResource(this, R.drawable.ic_notif)
        t.updateTile()
    }
}
