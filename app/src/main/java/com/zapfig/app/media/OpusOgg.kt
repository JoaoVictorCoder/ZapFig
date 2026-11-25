package com.zapfig.app.media

/** Codificador OGG Opus (libopus via JNI). Entrada: PCM 16 bits mono 48 kHz. */
object OpusOgg {
    init { System.loadLibrary("zapfignative") }

    fun encode(pcm48kMono: ShortArray, bitrate: Int = 64_000): ByteArray =
        nativeEncode(pcm48kMono, bitrate) ?: throw IllegalStateException("Falha ao codificar Opus")

    @JvmStatic private external fun nativeEncode(pcm: ShortArray, bitrate: Int): ByteArray?
}
