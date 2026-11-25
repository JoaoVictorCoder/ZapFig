package com.zapfig.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.zapfig.app.ui.MainActivity
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Atualização pelo GitHub Releases: consulta o último release, compara com a
 * versão instalada e baixa o APK certo para o processador do celular.
 * O Android só aceita a atualização se o APK for assinado com a mesma chave.
 */
object Updater {

    data class Release(val version: String, val notes: String, val apkUrl: String, val apkName: String, val size: Long)

    private const val API = "https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest"
    private const val CHANNEL = "updates"
    private const val NOTIF_ID = 2

    fun currentVersion(ctx: Context): String =
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "0"

    fun hasInternet(ctx: Context): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /** "1.2.10" > "1.2.9". Ignora prefixo "v" e sufixos tipo "-beta". */
    fun isNewer(remote: String, local: String): Boolean {
        fun parts(v: String) = v.trim().removePrefix("v").substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        val a = parts(remote); val b = parts(local)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /** Escolhe o APK do ABI do aparelho; se não houver, o universal. */
    fun pickAsset(names: List<String>): String? {
        val apks = names.filter { it.endsWith(".apk") }
        for (abi in Build.SUPPORTED_ABIS) apks.firstOrNull { it.contains(abi) }?.let { return it }
        return apks.firstOrNull { it.contains("universal") } ?: apks.firstOrNull()
    }

    /** Bloqueante (rode fora da main thread). Devolve o release se for mais novo que o instalado. */
    fun check(ctx: Context): Release? {
        if (!hasInternet(ctx)) return null
        val conn = (URL(API).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000; readTimeout = 10_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "ZapFig-Android")
        }
        try {
            if (conn.responseCode != 200) return null
            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            if (json.optBoolean("draft") || json.optBoolean("prerelease")) return null
            val tag = json.getString("tag_name")
            if (!isNewer(tag, currentVersion(ctx))) return null
            val assets = json.getJSONArray("assets")
            val list = (0 until assets.length()).map { assets.getJSONObject(it) }
            val name = pickAsset(list.map { it.getString("name") }) ?: return null
            val asset = list.first { it.getString("name") == name }
            return Release(
                version = tag.removePrefix("v"),
                notes = json.optString("body").take(1500),
                apkUrl = asset.getString("browser_download_url"),
                apkName = name,
                size = asset.optLong("size"),
            )
        } finally {
            conn.disconnect()
        }
    }

    /** Baixa o APK para o cache. onProgress recebe 0..1. Bloqueante. */
    fun download(ctx: Context, r: Release, onProgress: (Float) -> Unit): File {
        val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val out = File(dir, r.apkName)
        var url = URL(r.apkUrl)
        var conn: HttpURLConnection
        // GitHub redireciona para outro host; segue manualmente (até 5 vezes).
        var hops = 0
        while (true) {
            conn = (url.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 15_000; readTimeout = 30_000
                setRequestProperty("User-Agent", "ZapFig-Android")
            }
            val code = conn.responseCode
            if (code in 300..399 && hops++ < 5) {
                url = URL(url, conn.getHeaderField("Location")); conn.disconnect(); continue
            }
            if (code != 200) throw IllegalStateException("HTTP $code ao baixar atualização")
            break
        }
        val total = conn.contentLengthLong.takeIf { it > 0 } ?: r.size
        conn.inputStream.use { input ->
            out.outputStream().use { o ->
                val buf = ByteArray(64 * 1024)
                var done = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    o.write(buf, 0, n)
                    done += n
                    if (total > 0) onProgress(done.toFloat() / total)
                }
            }
        }
        conn.disconnect()
        return out
    }

    fun canInstall(ctx: Context) = ctx.packageManager.canRequestPackageInstalls()

    /** Abre a tela do Android para permitir "instalar apps desconhecidos" do ZapFig. */
    fun openInstallPermission(ctx: Context) {
        ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Abre o instalador do Android com o APK baixado. */
    fun install(ctx: Context, apk: File) {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", apk)
        ctx.startActivity(Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Usado quando o bot liga sem a tela aberta (atalho rápido, boot). */
    fun notifyAvailable(ctx: Context, r: Release) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Atualizações", NotificationManager.IMPORTANCE_DEFAULT))
        }
        val open = PendingIntent.getActivity(ctx, 2, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val b = if (Build.VERSION.SDK_INT >= 26) android.app.Notification.Builder(ctx, CHANNEL)
                else @Suppress("DEPRECATION") android.app.Notification.Builder(ctx)
        nm.notify(NOTIF_ID, b.setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("ZapFig ${r.version} disponível")
            .setContentText("Toque para atualizar")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build())
    }
}
