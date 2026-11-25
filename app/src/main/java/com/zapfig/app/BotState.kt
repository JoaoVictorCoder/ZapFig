package com.zapfig.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class Phase { OFF, STARTING, PAIRING, CONNECTING, ON, ERROR }

data class UiState(
    val phase: Phase = Phase.OFF,
    val status: String = "Desligado",
    val pairCode: String? = null,
    val paired: Boolean = false,
    val pairedNumber: String = "",
    val total: Int = 0,
    val animated: Int = 0,
    val logs: List<String> = emptyList(),
    val update: Updater.Release? = null,
)

/** Estado compartilhado entre o serviço (motor Go) e a interface. */
object BotState {
    private val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    val running get() = _ui.value.phase != Phase.OFF && _ui.value.phase != Phase.ERROR

    fun setPhase(p: Phase, status: String? = null) = _ui.update {
        it.copy(phase = p, status = status ?: it.status, pairCode = if (p == Phase.PAIRING) it.pairCode else null)
    }

    fun onStatus(s: String) {
        val phase = when {
            s.startsWith("Ligado") -> Phase.ON
            s.startsWith("Digite o código") -> Phase.PAIRING
            s.startsWith("Desligado") || s.startsWith("Sessão apagada") -> Phase.OFF
            s.startsWith("Deslogado") || s.startsWith("Banimento") || s.startsWith("Falha") ||
                s.startsWith("O código expirou") || s.startsWith("Sessão aberta") -> Phase.ERROR
            else -> if (_ui.value.phase == Phase.ON) Phase.CONNECTING else _ui.value.phase.takeIf { it != Phase.OFF } ?: Phase.CONNECTING
        }
        _ui.update { it.copy(phase = phase, status = s, paired = it.paired || phase == Phase.ON,
            pairCode = if (phase == Phase.PAIRING) it.pairCode else null) }
        log("• $s")
    }

    fun setPairCode(c: String) = _ui.update { it.copy(pairCode = c, phase = Phase.PAIRING) }

    fun setPaired(paired: Boolean, number: String) = _ui.update { it.copy(paired = paired, pairedNumber = number) }

    fun setStats(total: Int, animated: Int) = _ui.update { it.copy(total = total, animated = animated) }

    fun log(msg: String) = _ui.update {
        val line = "${time.format(Date())}  $msg"
        it.copy(logs = (listOf(line) + it.logs).take(300))
    }

    @Volatile var uiVisible = false

    fun setUpdate(r: Updater.Release?) = _ui.update { it.copy(update = r) }

    fun clearLogs() = _ui.update { it.copy(logs = emptyList()) }
}
