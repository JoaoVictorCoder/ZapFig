package com.zapfig.app.media

import android.content.Context
import java.io.File
import java.util.UUID

/**
 * Guarda um vídeo em disco enquanto o motor corta ele em várias figurinhas,
 * para não regravar o vídeo inteiro a cada trecho.
 */
object MediaCache {
    private fun dir(ctx: Context) = File(ctx.cacheDir, "seg").apply { mkdirs() }

    fun put(ctx: Context, data: ByteArray): String {
        val id = UUID.randomUUID().toString().replace("-", "").take(16)
        File(dir(ctx), "$id.mp4").writeBytes(data)
        return id
    }

    fun get(ctx: Context, id: String): File {
        require(id.matches(Regex("[0-9a-f]{16}"))) { "id inválido" }
        return File(dir(ctx), "$id.mp4").takeIf { it.exists() } ?: throw IllegalStateException("vídeo não está mais no cache")
    }

    fun remove(ctx: Context, id: String) { runCatching { get(ctx, id).delete() } }

    fun clear(ctx: Context) { dir(ctx).listFiles()?.forEach { it.delete() } }

    /** "id=ab12;start=6000;len=6000" -> mapa */
    fun args(s: String): Map<String, String> = s.split(';').mapNotNull {
        val k = it.substringBefore('=', ""); val v = it.substringAfter('=', "")
        if (k.isEmpty()) null else k to v
    }.toMap()
}
