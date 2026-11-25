package com.zapfig.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.zapfig.app.BotState
import com.zapfig.app.UiState

private val commands = listOf(
    Triple("📷", "Mande uma foto", "Vira figurinha (se a conversão automática estiver ligada)."),
    Triple("🎞️", "Mande um GIF", "Vira figurinha animada."),
    Triple("🎬", "Vídeo com legenda /s", "Vira figurinha animada com os primeiros segundos."),
    Triple("↩️", "Responder com /s", "Responda qualquer foto ou vídeo da conversa para converter."),
    Triple("🖼️", "Responder figurinha com /img", "Transforma a figurinha de volta em imagem."),
    Triple("✍️", "/t seu texto", "Cria uma figurinha só de texto."),
    Triple("🏷️", "/pack Nome | Autor", "Muda o nome do pacote e o autor das próximas figurinhas."),
    Triple("🚫", "Legenda /n", "Aquela foto ou GIF não vira figurinha."),
    Triple("❓", "/ajuda", "Mostra a lista de comandos no próprio WhatsApp."),
    Triple("🏓", "/ping", "Responde se o bot estiver ligado."),
)

@Composable
fun CommandsScreen(modifier: Modifier) {
    Column(modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp)) {
        Text("Comandos", fontSize = 28.sp, fontWeight = FontWeight.Black)
        Text("Funcionam só na conversa com você mesmo.", color = Muted, fontSize = 14.sp)
        Spacer(Modifier.height(16.dp))
        commands.forEach { (emoji, title, desc) ->
            Section {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(emoji, fontSize = 26.sp, modifier = Modifier.padding(end = 14.dp))
                    Column {
                        Text(title, fontWeight = FontWeight.Bold, fontFamily = if (title.contains("/")) FontFamily.Monospace else null)
                        Text(desc, color = Muted, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

@Composable
fun LogScreen(ui: UiState, modifier: Modifier) {
    val ctx = LocalContext.current
    Column(modifier.fillMaxSize().statusBarsPadding().padding(20.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Log", fontSize = 28.sp, fontWeight = FontWeight.Black, modifier = Modifier.weight(1f))
            TextButton({
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("log", ui.logs.joinToString("\n")))
                Toast.makeText(ctx, "Log copiado", Toast.LENGTH_SHORT).show()
            }) { Text("Copiar") }
            TextButton({ BotState.clearLogs() }) { Text("Limpar") }
        }
        if (ui.logs.isEmpty()) {
            Text("Nada por aqui ainda.", color = Muted)
        }
        LazyColumn {
            items(ui.logs) { line ->
                Text(line, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Muted,
                    modifier = Modifier.padding(vertical = 2.dp))
            }
        }
    }
}

@Composable
fun TermsDialog(onAccept: () -> Unit, onExit: () -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = { Text("Antes de começar") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("O ZapFig é um projeto independente e não oficial, sem ligação com o WhatsApp ou a Meta.")
                Spacer(Modifier.height(8.dp))
                Text("Ele conecta como um \"dispositivo conectado\" da sua conta usando um protocolo não oficial. " +
                    "Isso vai contra os Termos de Serviço do WhatsApp e pode levar à suspensão ou banimento do número.")
                Spacer(Modifier.height(8.dp))
                Text("O bot só responde na conversa com você mesmo e não manda nada para outras pessoas. " +
                    "Tudo é processado no seu celular.")
                Spacer(Modifier.height(8.dp))
                Text("Use por sua conta e risco. Recomendamos testar primeiro com um número secundário.",
                    fontWeight = FontWeight.Bold)
            }
        },
        confirmButton = { TextButton(onAccept) { Text("Entendi e aceito") } },
        dismissButton = { TextButton(onExit) { Text("Sair") } },
    )
}

