package com.zapfig.app.media

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import java.io.File
import java.nio.ByteOrder

/**
 * Som para figurinha com áudio: decodifica o áudio de qualquer mídia (vídeo,
 * áudio, mensagem de voz), corta nos primeiros [maxSeconds], converte para
 * mono 48 kHz e codifica em OGG Opus, o formato que o WhatsApp espera.
 */
object AudioExtractor {

    private const val RATE = 48_000
    private const val TIMEOUT_US = 10_000L

    /** Devolve os bytes do OGG, ou um array vazio se a mídia não tiver som. */
    fun toOggOpus(media: ByteArray, maxSeconds: Int, cacheDir: File): ByteArray {
        if (Build.VERSION.SDK_INT < 29) throw IllegalStateException("figurinha com som precisa do Android 10 ou mais novo")
        val input = File.createTempFile("snd", ".bin", cacheDir)
        val output = File.createTempFile("snd", ".ogg", cacheDir)
        try {
            input.writeBytes(media)
            val pcm = decodeMono(input, maxSeconds) ?: return ByteArray(0)
            if (pcm.first.isEmpty()) return ByteArray(0)
            val resampled = resample(pcm.first, pcm.second, RATE)
            encodeOpus(resampled, output)
            return output.readBytes()
        } finally {
            input.delete()
            output.delete()
        }
    }

    /** Decodifica para PCM mono 16 bits. Devolve (amostras, taxa) ou null se não houver trilha de áudio. */
    private fun decodeMono(file: File, maxSeconds: Int): Pair<ShortArray, Int>? {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(file.absolutePath)
            val track = (0 until ex.trackCount).firstOrNull {
                ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null
            val fmt = ex.getTrackFormat(track)
            ex.selectTrack(track)
            val dec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
            dec.configure(fmt, null, null, 0)
            dec.start()

            var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var isFloat = false
            val maxUs = maxSeconds * 1_000_000L
            val out = ShortList()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            val deadline = System.currentTimeMillis() + 30_000

            while (!outputDone) {
                if (System.currentTimeMillis() > deadline) throw IllegalStateException("decodificação do áudio travou")
                if (!inputDone) {
                    val i = dec.dequeueInputBuffer(TIMEOUT_US)
                    if (i >= 0) {
                        val buf = dec.getInputBuffer(i)!!
                        val n = ex.readSampleData(buf, 0)
                        if (n < 0 || ex.sampleTime > maxUs) {
                            dec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            dec.queueInputBuffer(i, 0, n, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val o = dec.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = dec.outputFormat
                        rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        isFloat = f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                            f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                    }
                    o >= 0 -> {
                        val buf = dec.getOutputBuffer(o)!!.order(ByteOrder.nativeOrder())
                        buf.position(info.offset); buf.limit(info.offset + info.size)
                        if (info.presentationTimeUs <= maxUs) {
                            if (isFloat) {
                                val fb = buf.asFloatBuffer()
                                while (fb.remaining() >= channels) {
                                    var s = 0f
                                    repeat(channels) { s += fb.get() }
                                    out.add(((s / channels).coerceIn(-1f, 1f) * 32767).toInt().toShort())
                                }
                            } else {
                                val sb = buf.asShortBuffer()
                                while (sb.remaining() >= channels) {
                                    var s = 0
                                    repeat(channels) { s += sb.get() }
                                    out.add((s / channels).toShort())
                                }
                            }
                        }
                        dec.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
            dec.stop(); dec.release()
            // corta exatamente na duração máxima
            return Pair(out.toArray().let { if (it.size > rate * maxSeconds) it.copyOf(rate * maxSeconds) else it }, rate)
        } finally {
            ex.release()
        }
    }

    /** Reamostragem linear simples (ex.: 44,1 kHz -> 48 kHz). */
    private fun resample(src: ShortArray, from: Int, to: Int): ShortArray {
        if (from == to || src.isEmpty()) return src
        val n = (src.size.toLong() * to / from).toInt()
        val dst = ShortArray(n)
        val step = from.toDouble() / to
        for (i in 0 until n) {
            val pos = i * step
            val j = pos.toInt()
            val frac = pos - j
            val a = src[j.coerceAtMost(src.size - 1)]
            val b = src[(j + 1).coerceAtMost(src.size - 1)]
            dst[i] = (a + (b - a) * frac).toInt().toShort()
        }
        return dst
    }

    private fun encodeOpus(pcm: ShortArray, out: File) {
        val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, RATE, 1).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, 64_000)
        }
        val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
        enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        enc.start()
        val muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG)
        var track = -1
        var started = false
        val info = MediaCodec.BufferInfo()
        var fed = 0
        var inputDone = false
        val deadline = System.currentTimeMillis() + 30_000
        try {
            while (true) {
                if (System.currentTimeMillis() > deadline) throw IllegalStateException("codificação Opus travou")
                if (!inputDone) {
                    val i = enc.dequeueInputBuffer(TIMEOUT_US)
                    if (i >= 0) {
                        val buf = enc.getInputBuffer(i)!!.order(ByteOrder.nativeOrder())
                        buf.clear()
                        val ptsUs = fed * 1_000_000L / RATE
                        // blocos de 20 ms (960 amostras), o tamanho de quadro do Opus
                        val room = (buf.capacity() / 2 / 960) * 960
                        val count = minOf(room.coerceAtLeast(960), pcm.size - fed)
                        if (count <= 0) {
                            enc.queueInputBuffer(i, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            buf.asShortBuffer().put(pcm, fed, count)
                            enc.queueInputBuffer(i, 0, count * 2, ptsUs, 0)
                            fed += count
                        }
                    }
                }
                val o = enc.dequeueOutputBuffer(info, TIMEOUT_US)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    track = muxer.addTrack(enc.outputFormat); muxer.start(); started = true
                } else if (o >= 0) {
                    val buf = enc.getOutputBuffer(o)!!
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0 && started) {
                        buf.position(info.offset); buf.limit(info.offset + info.size)
                        muxer.writeSampleData(track, buf, info)
                    }
                    enc.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        } finally {
            runCatching { enc.stop() }
            enc.release()
            if (started) runCatching { muxer.stop() }
            muxer.release()
        }
    }

    /** Lista de shorts que cresce sem boxing. */
    private class ShortList {
        private var a = ShortArray(48_000)
        private var n = 0
        fun add(v: Short) {
            if (n == a.size) a = a.copyOf(a.size * 2)
            a[n++] = v
        }
        fun toArray(): ShortArray = a.copyOf(n)
    }
}
