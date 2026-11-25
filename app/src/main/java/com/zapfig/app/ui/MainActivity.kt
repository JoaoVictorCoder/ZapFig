package com.zapfig.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.zapfig.app.BotService
import com.zapfig.app.BotState
import com.zapfig.app.Prefs
import com.zapfig.engine.bot.Bot
import kotlin.concurrent.thread

class MainActivity : ComponentActivity() {

    private lateinit var prefs: Prefs

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        BotState.setStats(prefs.totalStickers, prefs.animatedStickers)
        BotService.pushConfig(this)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        setContent {
            ZapFigTheme {
                val ui by BotState.ui.collectAsState()
                var tab by rememberSaveable { mutableIntStateOf(0) }
                var accepted by remember { mutableStateOf(prefs.acceptedTerms) }

                if (!accepted) {
                    TermsDialog(onAccept = { prefs.acceptedTerms = true; accepted = true }, onExit = { finish() })
                }

                Scaffold(
                    containerColor = Bg,
                    bottomBar = {
                        NavigationBar(containerColor = Surface1) {
                            val items = listOf(
                                Triple("Início", Icons.Filled.Home, 0),
                                Triple("Ajustes", Icons.Filled.Settings, 1),
                                Triple("Comandos", Icons.Filled.Info, 2),
                                Triple("Log", Icons.AutoMirrored.Filled.List, 3),
                            )
                            items.forEach { (label, icon, i) ->
                                NavigationBarItem(
                                    selected = tab == i,
                                    onClick = { tab = i },
                                    icon = { Icon(icon, contentDescription = label) },
                                    label = { Text(label) },
                                    colors = NavigationBarItemDefaults.colors(indicatorColor = GreenDark),
                                )
                            }
                        }
                    }
                ) { pad ->
                    val m = Modifier.padding(pad)
                    when (tab) {
                        0 -> HomeScreen(ui, prefs, m)
                        1 -> SettingsScreen(ui, prefs, m, onLoggedOut = { refreshPaired() })
                        2 -> CommandsScreen(m)
                        else -> LogScreen(ui, m)
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPaired()
    }

    private fun refreshPaired() = thread {
        val num = try { Bot.pairedNumber(filesDir.absolutePath) } catch (e: Exception) { "" }
        BotState.setPaired(num.isNotEmpty(), num)
    }
}
