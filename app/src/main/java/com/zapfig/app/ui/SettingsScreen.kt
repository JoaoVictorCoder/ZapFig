package com.zapfig.app.ui

import android.annotation.SuppressLint
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zapfig.app.BotService
import com.zapfig.app.BotState
import com.zapfig.app.Prefs
import com.zapfig.app.R
import com.zapfig.app.ToggleTileService
import com.zapfig.app.UiState
import com.zapfig.app.media.CropMode
import com.zapfig.engine.bot.Bot
import kotlin.concurrent.thread

@Composable
fun SettingsScreen(ui: UiState, prefs: Prefs, modifier: Modifier, onLoggedOut: () -> Unit) {
    val ctx = LocalContext.current
    var pack by remember { mutableStateOf(prefs.pack) }
    var author by remember { mutableStateOf(prefs.author) }
    var autoImage by remember { mutableStateOf(prefs.autoImage) }
    var autoGif by remember { mutableStateOf(prefs.autoGif) }
    var crop by remember { mutableStateOf(prefs.crop) }
    var seconds by remember { mutableFloatStateOf(prefs.maxSeconds.toFloat()) }
    var fps by remember { mutableFloatStateOf(prefs.fps.toFloat()) }
    var boot by remember { mutableStateOf(prefs.startOnBoot) }
    var confirmLogout by remember { mutableStateOf(false) }

    fun save() {
        prefs.pack = pack.trim().ifEmpty { "ZapFig" }
        prefs.author = author.trim()
        prefs.autoImage = autoImage
        prefs.autoGif = autoGif
        BotService.pushConfig(ctx)
    }

    Column(modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp)) {
        Text("Ajustes", fontSize = 28.sp, fontWeight = FontWeight.Black)
        Spacer(Modifier.height(16.dp))

        Group("Pacote da figurinha")
        Section {
            Text("Aparece embaixo da figurinha no WhatsApp (\"Pacote • Autor\").", color = Muted, fontSize = 13.sp)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(pack, { pack = it.take(40); save() }, label = { Text("Nome do pacote") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(author, { author = it.take(40); save() }, label = { Text("Autor") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
        }

        Group("Conversão automática")
        Section {
            SwitchRow("Fotos viram figurinha", "Sem precisar de comando", autoImage) { autoImage = it; save() }
            SwitchRow("GIFs viram figurinha animada", "Vídeos normais precisam da legenda /s", autoGif) { autoGif = it; save() }
        }

        Group("Enquadramento")
        Section {
            val modes = listOf(CropMode.FIT to "Encaixar", CropMode.CROP to "Recortar", CropMode.STRETCH to "Esticar")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                modes.forEachIndexed { i, (mode, label) ->
                    SegmentedButton(
                        selected = crop == mode,
                        onClick = { crop = mode; prefs.crop = mode },
                        shape = SegmentedButtonDefaults.itemShape(i, modes.size),
                    ) { Text(label) }
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(when (crop) {
                CropMode.FIT -> "Mostra a imagem inteira, com fundo transparente."
                CropMode.CROP -> "Corta o centro em um quadrado."
                CropMode.STRETCH -> "Estica a imagem até preencher o quadrado."
            }, color = Muted, fontSize = 13.sp)
        }

        Group("Figurinhas animadas")
        Section {
            Text("Duração máxima: ${seconds.toInt()} s")
            Slider(seconds, { seconds = it }, valueRange = 1f..8f, steps = 6,
                onValueChangeFinished = { prefs.maxSeconds = seconds.toInt() })
            Text("Quadros por segundo: ${fps.toInt()}")
            Slider(fps, { fps = it }, valueRange = 5f..15f, steps = 9,
                onValueChangeFinished = { prefs.fps = fps.toInt() })
            Text("Mais longo ou mais fluido = mais lento e qualidade menor (limite do WhatsApp: 500 KB).",
                color = Muted, fontSize = 13.sp)
        }

        Group("Sistema")
        Section {
            SwitchRow("Ligar junto com o celular", "Se estava ligado antes de reiniciar", boot) {
                boot = it; prefs.startOnBoot = it
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton({ askBatteryExemption(ctx) }, Modifier.weight(1f)) { Text("Liberar bateria") }
                if (Build.VERSION.SDK_INT >= 33) {
                    OutlinedButton({ addTile(ctx) }, Modifier.weight(1f)) { Text("Atalho rápido") }
                }
            }
            Spacer(Modifier.height(6.dp))
            Text("Em celulares Xiaomi/Poco, ative também \"Início automático\" e \"Sem restrições\" de bateria para o ZapFig.",
                color = Muted, fontSize = 13.sp)
        }

        Group("Conta")
        Section {
            Text(if (ui.paired) "Conectado como +${ui.pairedNumber}" else "Nenhuma conta conectada", fontSize = 14.sp)
            if (ui.paired) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton({ confirmLogout = true }, Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Danger)) { Text("Sair da conta") }
            }
        }

        Group("Sobre")
        Section {
            Text("ZapFig ${appVersion(ctx)} • motor ${Bot.Version}", fontSize = 14.sp)
            Spacer(Modifier.height(4.dp))
            Text("Projeto independente, sem ligação com o WhatsApp ou a Meta. Tudo roda no seu celular: " +
                "nenhuma mensagem passa por servidores do ZapFig.", color = Muted, fontSize = 13.sp)
        }
    }

    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            title = { Text("Sair da conta?") },
            text = { Text("O ZapFig será removido dos dispositivos conectados do seu WhatsApp. Para usar de novo, será preciso parear outra vez.") },
            confirmButton = {
                TextButton({
                    confirmLogout = false
                    thread {
                        try { Bot.logout(ctx.filesDir.absolutePath) } catch (e: Exception) { BotState.log("Erro ao sair: ${e.message}") }
                        BotService.stop(ctx)
                        onLoggedOut()
                    }
                }) { Text("Sair", color = Danger) }
            },
            dismissButton = { TextButton({ confirmLogout = false }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun Group(title: String) {
    Text(title.uppercase(), color = Green, fontSize = 12.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 4.dp, bottom = 6.dp, top = 8.dp))
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp)
            Text(subtitle, color = Muted, fontSize = 12.sp)
        }
        Switch(checked, onChange)
    }
}

private fun appVersion(ctx: Context): String =
    try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "" } catch (e: Exception) { "" }

@SuppressLint("BatteryLife")
private fun askBatteryExemption(ctx: Context) {
    val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
    if (pm.isIgnoringBatteryOptimizations(ctx.packageName)) {
        Toast.makeText(ctx, "Já está liberado ✅", Toast.LENGTH_SHORT).show()
        return
    }
    try {
        ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")))
    } catch (e: Exception) {
        ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }
}

private fun addTile(ctx: Context) {
    if (Build.VERSION.SDK_INT < 33) return
    val sbm = ctx.getSystemService(StatusBarManager::class.java)
    sbm.requestAddTileService(
        ComponentName(ctx, ToggleTileService::class.java), "ZapFig",
        Icon.createWithResource(ctx, R.drawable.ic_notif), ctx.mainExecutor,
    ) { result ->
        val msg = when (result) {
            StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED -> "Atalho adicionado ✅"
            StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> "O atalho já está lá"
            else -> "Adicione pelo lápis das Configurações rápidas"
        }
        Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
    }
}
