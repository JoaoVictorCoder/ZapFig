package com.zapfig.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zapfig.app.BotService
import com.zapfig.app.Phase
import com.zapfig.app.Prefs
import com.zapfig.app.UiState

@Composable
fun HomeScreen(ui: UiState, prefs: Prefs, modifier: Modifier) {
    val ctx = LocalContext.current
    var phone by remember { mutableStateOf(prefs.phone) }
    val on = ui.phase != Phase.OFF && ui.phase != Phase.ERROR

    Column(
        modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("ZapFig", fontSize = 30.sp, fontWeight = FontWeight.Black)
                Text("Seu número vira um bot de figurinhas", color = Muted, fontSize = 14.sp)
            }
        }

        Spacer(Modifier.height(32.dp))
        PowerButton(ui.phase) {
            if (on) {
                BotService.stop(ctx)
            } else {
                val digits = phone.filter { it.isDigit() }
                if (!ui.paired && digits.length < 12) {
                    Toast.makeText(ctx, "Digite seu número com 55 + DDD", Toast.LENGTH_LONG).show()
                } else {
                    prefs.phone = digits
                    BotService.start(ctx, digits)
                }
            }
        }
        Spacer(Modifier.height(18.dp))
        Text(ui.status, textAlign = TextAlign.Center, fontSize = 16.sp,
            color = if (ui.phase == Phase.ERROR) Danger else MaterialTheme.colorScheme.onBackground)

        Spacer(Modifier.height(24.dp))

        AnimatedVisibility(ui.pairCode != null) {
            ui.pairCode?.let { PairCodeCard(it) }
        }

        if (!ui.paired && ui.pairCode == null) {
            Section {
                Text("Primeira vez? Conecte seu WhatsApp", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text("Digite seu número com DDI e DDD, toque em LIGAR e use o código que aparecer.",
                    color = Muted, fontSize = 13.sp)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = phone,
                    onValueChange = { phone = it.filter { c -> c.isDigit() }.take(15) },
                    label = { Text("Seu número") },
                    placeholder = { Text("5516999999999") },
                    singleLine = true,
                    enabled = !on,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        if (ui.paired) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Stat("Figurinhas", ui.total.toString(), Modifier.weight(1f))
                Stat("Animadas", ui.animated.toString(), Modifier.weight(1f))
            }
            Spacer(Modifier.height(12.dp))
            Section {
                Text("Como usar", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Text("Abra a conversa com você mesmo e mande uma foto ou GIF. Ela volta como figurinha. " +
                    "Mande /ajuda lá pra ver todos os comandos.", color = Muted, fontSize = 14.sp)
                if (ui.pairedNumber.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = { openSelfChat(ctx, ui.pairedNumber) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                    ) { Text("Abrir conversa comigo") }
                }
            }
        }
    }
}

@Composable
private fun PowerButton(phase: Phase, onClick: () -> Unit) {
    val on = phase == Phase.ON
    val busy = phase == Phase.STARTING || phase == Phase.CONNECTING || phase == Phase.PAIRING
    val color by animateColorAsState(
        when {
            on -> Green
            busy -> Amber
            phase == Phase.ERROR -> Danger
            else -> Surface2
        }, label = "cor"
    )
    val pulse = rememberInfiniteTransition(label = "pulso")
    val s by pulse.animateFloat(1f, if (busy) 1.06f else 1f,
        infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "escala")

    Box(
        Modifier.size(210.dp).scale(s).clip(CircleShape)
            .background(color.copy(alpha = 0.18f))
            .border(3.dp, color, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(160.dp).clip(CircleShape).background(color.copy(alpha = if (on) 1f else 0.35f)),
            contentAlignment = Alignment.Center) {
            Text(
                when {
                    on -> "DESLIGAR"
                    busy -> "PARAR"
                    else -> "LIGAR"
                },
                fontSize = 24.sp, fontWeight = FontWeight.Black,
                color = if (on) Color(0xFF00210D) else Color.White,
            )
        }
    }
}

@Composable
private fun PairCodeCard(code: String) {
    val ctx = LocalContext.current
    val pretty = if (code.length == 8) code.substring(0, 4) + "-" + code.substring(4) else code
    Section(highlight = true) {
        Text("Seu código de pareamento", fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(pretty, fontSize = 40.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace,
            color = Green, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text("1. Abra o WhatsApp\n2. ⋮ › Dispositivos conectados › Conectar dispositivo\n" +
            "3. Toque em \"Conectar com número de telefone\"\n4. Digite o código acima",
            color = Muted, fontSize = 13.sp)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("código", code))
                Toast.makeText(ctx, "Código copiado", Toast.LENGTH_SHORT).show()
            }, modifier = Modifier.weight(1f)) { Text("Copiar") }
            Button(onClick = {
                val i = ctx.packageManager.getLaunchIntentForPackage("com.whatsapp")
                    ?: ctx.packageManager.getLaunchIntentForPackage("com.whatsapp.w4b")
                if (i != null) ctx.startActivity(i)
                else Toast.makeText(ctx, "WhatsApp não encontrado", Toast.LENGTH_SHORT).show()
            }, modifier = Modifier.weight(1f)) { Text("Abrir WhatsApp") }
        }
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = Surface1), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(value, fontSize = 28.sp, fontWeight = FontWeight.Black, color = Green)
            Text(label, color = Muted, fontSize = 13.sp)
        }
    }
}

@Composable
fun Section(highlight: Boolean = false, content: @Composable () -> Unit) {
    Card(
        Modifier.fillMaxWidth().padding(bottom = 12.dp),
        colors = CardDefaults.cardColors(containerColor = if (highlight) Surface2 else Surface1),
        shape = RoundedCornerShape(16.dp),
        border = if (highlight) androidx.compose.foundation.BorderStroke(1.dp, GreenDark) else null,
    ) {
        Column(Modifier.padding(16.dp)) { content() }
    }
}

private fun openSelfChat(ctx: Context, number: String) {
    try {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$number")))
    } catch (e: Exception) {
        Toast.makeText(ctx, "Não consegui abrir o WhatsApp", Toast.LENGTH_SHORT).show()
    }
}

