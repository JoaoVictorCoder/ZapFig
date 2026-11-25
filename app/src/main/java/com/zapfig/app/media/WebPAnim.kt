package com.zapfig.app.media

import android.graphics.Bitmap

/** Encoder de WebP animado (libwebp via JNI). */
class WebPAnim(width: Int, height: Int) : AutoCloseable {
    private var handle: Long = nativeNew(width, height)

    init {
        if (handle == 0L) throw IllegalStateException("Falha ao criar encoder WebP")
    }

    fun addFrame(frame: Bitmap, timestampMs: Int, quality: Float) {
        val r = nativeAddFrame(handle, frame, timestampMs, quality)
        if (r != 0) throw IllegalStateException("Falha ao adicionar frame ($r)")
    }

    fun assemble(endTimestampMs: Int): ByteArray =
        nativeAssemble(handle, endTimestampMs) ?: throw IllegalStateException("Falha ao montar WebP")

    override fun close() {
        if (handle != 0L) { nativeFree(handle); handle = 0L }
    }

    companion object {
        init { System.loadLibrary("zapfignative") }
        @JvmStatic private external fun nativeNew(width: Int, height: Int): Long
        @JvmStatic private external fun nativeAddFrame(handle: Long, bitmap: Bitmap, timestampMs: Int, quality: Float): Int
        @JvmStatic private external fun nativeAssemble(handle: Long, endTimestampMs: Int): ByteArray?
        @JvmStatic private external fun nativeFree(handle: Long)
    }
}
