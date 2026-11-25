package com.zapfig.app.media

import android.graphics.Bitmap

/** Lê os frames de um WebP animado (libwebp via JNI). */
class WebPAnimDecoder(webp: ByteArray) : AutoCloseable {
    private var handle = nativeOpen(webp)
    val width: Int
    val height: Int
    val frameCount: Int

    init {
        if (handle == 0L) throw IllegalArgumentException("Figurinha animada inválida")
        val i = nativeInfo(handle)
        width = i[0]; height = i[1]; frameCount = i[2]
    }

    /** Preenche [into] com o próximo frame. Devolve o fim do frame em ms, ou -1 se acabou. */
    fun next(into: Bitmap): Int = nativeNext(handle, into)

    override fun close() {
        if (handle != 0L) { nativeClose(handle); handle = 0L }
    }

    companion object {
        init { System.loadLibrary("zapfignative") }
        @JvmStatic private external fun nativeOpen(webp: ByteArray): Long
        @JvmStatic private external fun nativeInfo(handle: Long): IntArray
        @JvmStatic private external fun nativeNext(handle: Long, bitmap: Bitmap): Int
        @JvmStatic private external fun nativeClose(handle: Long)

        /** WebP com animação tem o chunk VP8X com a flag 0x02. */
        fun isAnimated(webp: ByteArray): Boolean =
            webp.size > 21 && String(webp, 12, 4, Charsets.US_ASCII) == "VP8X" && (webp[20].toInt() and 0x02) != 0
    }
}
