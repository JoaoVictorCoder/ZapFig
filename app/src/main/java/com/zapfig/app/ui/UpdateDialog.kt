package com.zapfig.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zapfig.app.Updater
import java.io.File
import kotlin.concurrent.thread

private enum class Step { ASK, DOWNLOADING, READY, ERROR }

@Composable
fun UpdateDialog(release: Updater.Release, onLater: () -> Unit, onSkip: () -> Unit) {
    val ctx = LocalContext.current
    var step by remember { mutableStateOf(Step.ASK) }
    var progress by remember { mutableFloatStateOf(0f) }
    var error by remember { mutableStateOf("") }
    var apk by remember { mutableStateOf<File?>(null) }

    fun tryInstall(file: File) {
        if (!Updater.canInstall(ctx)) {
            Updater.openInstallPermission(ctx)
        } else {
            Updater.install(ctx, file)
        }
    }

    fun startDownload() {
        step = Step.DOWNLOADING
        thread {
            try {
                val f = Updater.download(ctx, release) { p -> progress = p }
                apk = f
                step = Step.READY
                tryInstall(f)
            } catch (e: Exception) {
                error = e.message ?: "erro desconhecido"
                step = Step.ERROR
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (step != Step.DOWNLOADING) onLater() },
        title = { Text("Atualização ${release.version} disponível") },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                when (step) {
                    Step.ASK -> {
                        Text("Você está na versão ${Updater.currentVersion(ctx)}. Quer atualizar agora?")
                        if (release.size > 0) {
                            Text("Download: %.1f MB".format(release.size / 1_048_576f), color = Muted, fontSize = 13.sp)
                        }
                        if (release.notes.isNotBlank()) {
                            Spacer(Modifier.height(12.dp))
                            Text("Novidades", color = Green, fontSize = 13.sp)
                            Text(release.notes, fontSize = 13.sp, color = Muted)
                        }
                    }
                    Step.DOWNLOADING -> {
                        Text("Baixando… ${(progress * 100).toInt()}%")
                        Spacer(Modifier.height(12.dp))
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    }
                    Step.READY -> {
                        Text("Download concluído. Confirme a instalação na tela do Android.")
                        if (!Updater.canInstall(ctx)) {
                            Spacer(Modifier.height(8.dp))
                            Text("Primeiro permita que o ZapFig instale apps e depois toque em Instalar.",
                                color = Muted, fontSize = 13.sp)
                        }
                    }
                    Step.ERROR -> Text("Não consegui baixar: $error", color = Danger)
                }
            }
        },
        confirmButton = {
            when (step) {
                Step.ASK -> TextButton(::startDownload) { Text("Atualizar") }
                Step.READY -> TextButton({ apk?.let(::tryInstall) }) { Text("Instalar") }
                Step.ERROR -> TextButton(::startDownload) { Text("Tentar de novo") }
                Step.DOWNLOADING -> {}
            }
        },
        dismissButton = {
            if (step == Step.ASK) {
                Column {
                    TextButton(onLater) { Text("Agora não") }
                    TextButton(onSkip) { Text("Pular esta versão", color = Muted) }
                }
            } else if (step != Step.DOWNLOADING) {
                TextButton(onLater) { Text("Fechar") }
            }
        },
    )
}
