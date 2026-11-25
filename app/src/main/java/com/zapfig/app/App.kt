package com.zapfig.app

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Registra crashes para aparecerem na aba Log na próxima abertura. */
class App : Application() {

    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val sw = StringWriter()
                error.printStackTrace(PrintWriter(sw))
                val when_ = SimpleDateFormat("dd/MM HH:mm:ss", Locale.getDefault()).format(Date())
                crashFile(this).writeText("[$when_] ZapFig ${appVersion()} • thread ${thread.name}\n$sw")
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun appVersion() = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    } catch (_: Exception) { "?" }

    companion object {
        private fun crashFile(ctx: Context) = File(ctx.filesDir, "last_crash.txt")

        /** Devolve (e apaga) o relatório do último crash, se houver. Inclui crashes nativos (Android 11+). */
        fun takeCrashReport(ctx: Context): String? {
            val parts = mutableListOf<String>()
            val f = crashFile(ctx)
            if (f.exists()) {
                parts += f.readText()
                f.delete()
            }
            if (Build.VERSION.SDK_INT >= 30) {
                val sp = ctx.getSharedPreferences("zapfig", Context.MODE_PRIVATE)
                val seen = sp.getLong("last_exit_seen", 0)
                val am = ctx.getSystemService(ActivityManager::class.java)
                val exits = runCatching { am.getHistoricalProcessExitReasons(ctx.packageName, 0, 5) }.getOrDefault(emptyList())
                exits.filter { it.timestamp > seen && it.reason == ApplicationExitInfo.REASON_CRASH_NATIVE }
                    .forEach { e ->
                        val t = SimpleDateFormat("dd/MM HH:mm:ss", Locale.getDefault()).format(Date(e.timestamp))
                        parts += "[$t] crash nativo: ${e.description ?: "sem descrição"}"
                    }
                exits.maxOfOrNull { it.timestamp }?.let { sp.edit().putLong("last_exit_seen", it).apply() }
            }
            return parts.takeIf { it.isNotEmpty() }?.joinToString("\n\n")
        }
    }
}
