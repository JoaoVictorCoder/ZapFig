package com.zapfig.app.media

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONObject
import java.io.File

/**
 * Baixa vídeo/áudio de links (YouTube, Instagram, TikTok, X…) com yt-dlp,
 * rodando no próprio celular via youtubedl-android.
 */
object Downloader {

    @Volatile private var ready = false
    private const val UPDATE_EVERY_MS = 24 * 60 * 60 * 1000L

    @Synchronized
    fun init(ctx: Context) {
        if (ready) return
        YoutubeDL.getInstance().init(ctx.applicationContext)
        FFmpeg.getInstance().init(ctx.applicationContext)
        ready = true
    }

    /** Atualiza o yt-dlp no máximo uma vez por dia (os sites mudam o tempo todo). */
    fun updateIfDue(ctx: Context, log: (String) -> Unit) {
        val sp = ctx.getSharedPreferences("zapfig", Context.MODE_PRIVATE)
        val last = sp.getLong("ytdlp_updated_at", 0)
        if (System.currentTimeMillis() - last < UPDATE_EVERY_MS) return
        try {
            init(ctx)
            val status = YoutubeDL.getInstance().updateYoutubeDL(ctx.applicationContext, YoutubeDL.UpdateChannel._STABLE)
            sp.edit().putLong("ytdlp_updated_at", System.currentTimeMillis()).apply()
            log("yt-dlp: ${status?.name?.lowercase() ?: "ok"} (${YoutubeDL.getInstance().versionName(ctx) ?: "?"})")
        } catch (e: Throwable) {
            log("Não consegui atualizar o yt-dlp: ${e.message}")
        }
    }

    /**
     * Baixa e devolve um JSON para o motor:
     * {"path","mime","title","duration","width","height","thumb"}
     */
    fun fetch(ctx: Context, url: String, audioOnly: Boolean, log: (String) -> Unit): String {
        try {
            init(ctx)
        } catch (e: Throwable) {
            throw IllegalStateException("não consegui preparar o yt-dlp: ${e.message}", e)
        }
        updateIfDue(ctx, log)
        val dir = File(ctx.cacheDir, "dl/${System.currentTimeMillis()}").apply { mkdirs() }
        val req = YoutubeDLRequest(url).apply {
            addOption("-o", "${dir.absolutePath}/media.%(ext)s")
            addOption("--no-playlist")
            addOption("--no-mtime")
            addOption("--write-info-json")
            addOption("--max-filesize", "500M")
            addOption("--socket-timeout", "20")
            if (audioOnly) {
                addOption("-f", "ba/b")
                addOption("-x")
                addOption("--audio-format", "mp3")
                addOption("--audio-quality", "5")
            } else {
                // até 720p, preferindo H.264 + AAC (o que o WhatsApp toca em qualquer celular)
                addOption("-f", "bv*[height<=720]+ba/b[height<=720]/bv*+ba/b")
                addOption("-S", "vcodec:h264,res:720,acodec:aac")
                addOption("--merge-output-format", "mp4")
            }
        }
        var lastPct = -10
        val resp = YoutubeDL.getInstance().execute(req, null) { progress, _, _ ->
            val p = progress.toInt()
            if (p >= lastPct + 25) { lastPct = p; log("Download: $p%") }
        }
        if (resp.exitCode != 0) throw IllegalStateException(resp.err.ifBlank { resp.out })

        val media = dir.listFiles()?.firstOrNull {
            it.name.startsWith("media.") && !it.name.endsWith(".json") && !it.name.endsWith(".part")
        } ?: throw IllegalStateException("o yt-dlp não gerou nenhum arquivo (${resp.err.take(300)})")

        val info = File(dir, "media.info.json").takeIf { it.exists() }?.let {
            runCatching { JSONObject(it.readText()) }.getOrNull()
        }
        val out = JSONObject()
            .put("path", media.absolutePath)
            .put("title", info?.optString("title").orEmpty())
            .put("mime", mimeOf(media))

        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(media.absolutePath)
            val durMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            out.put("duration", ((durMs + 500) / 1000).toInt())
            if (!audioOnly) {
                var w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                var h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                if (rot == 90 || rot == 270) { val t = w; w = h; h = t }
                out.put("width", w).put("height", h)
                // miniatura pequena para a prévia do WhatsApp
                r.getScaledFrameAtTime(minOf(1_000_000L, durMs * 500), MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 320, 320)?.let { bmp ->
                    val thumb = File(dir, "thumb.jpg")
                    thumb.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 70, it) }
                    out.put("thumb", thumb.absolutePath)
                }
            }
        } catch (e: Exception) {
            log("Aviso: não li os detalhes do arquivo (${e.message})")
        } finally {
            r.release()
        }
        return out.toString()
    }

    private fun mimeOf(f: File) = when (f.extension.lowercase()) {
        "mp4", "m4v" -> "video/mp4"
        "webm" -> "video/webm"
        "mkv" -> "video/x-matroska"
        "mp3" -> "audio/mpeg"
        "m4a" -> "audio/mp4"
        "opus", "ogg" -> "audio/ogg"
        else -> "application/octet-stream"
    }

    /** Limpa downloads que sobraram (ex.: app fechado no meio). */
    fun cleanup(ctx: Context) {
        File(ctx.cacheDir, "dl").listFiles()?.forEach { it.deleteRecursively() }
    }
}
