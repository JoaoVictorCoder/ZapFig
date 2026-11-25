package com.zapfig.app.media

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File

/**
 * Figurinha animada -> "GIF" do WhatsApp (que na verdade é um MP4 H.264 em loop).
 * Usa o encoder de vídeo do próprio Android (MediaCodec), sem ffmpeg.
 */
object GifMaker {

    private const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC
    private const val TIMEOUT_US = 10_000L

    /** Devolve os bytes do MP4. [background] preenche as áreas transparentes. */
    fun fromAnimatedWebP(webp: ByteArray, cacheDir: File, background: Int = Color.WHITE): ByteArray {
        WebPAnimDecoder(webp).use { dec ->
            // H.264 exige largura/altura pares.
            val w = dec.width and 1.inv()
            val h = dec.height and 1.inv()
            val raw = Bitmap.createBitmap(dec.width, dec.height, Bitmap.Config.ARGB_8888)
            val frame = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(frame)
            val paint = Paint(Paint.FILTER_BITMAP_FLAG)
            val argb = IntArray(w * h)

            val out = File.createTempFile("gif", ".mp4", cacheDir)
            val format = MediaFormat.createVideoFormat(MIME, w, h).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                setInteger(MediaFormat.KEY_BIT_RATE, 2_000_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, 25)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            val codec = MediaCodec.createEncoderByType(MIME)
            val muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var track = -1
            var muxing = false
            val info = MediaCodec.BufferInfo()

            fun drain(endOfStream: Boolean) {
                val deadline = System.currentTimeMillis() + 10_000
                while (true) {
                    val idx = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                    when {
                        idx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                            if (!endOfStream) return
                            if (System.currentTimeMillis() > deadline) throw IllegalStateException("Encoder não finalizou")
                        }
                        idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            track = muxer.addTrack(codec.outputFormat); muxer.start(); muxing = true
                        }
                        idx >= 0 -> {
                            val buf = codec.getOutputBuffer(idx)!!
                            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                            if (info.size > 0 && muxing) {
                                buf.position(info.offset); buf.limit(info.offset + info.size)
                                muxer.writeSampleData(track, buf, info)
                            }
                            codec.releaseOutputBuffer(idx, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                        }
                    }
                }
            }

            try {
                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                codec.start()
                var startMs = 0
                var frames = 0
                while (true) {
                    val endMs = dec.next(raw)
                    if (endMs < 0) break
                    canvas.drawColor(background)
                    canvas.drawBitmap(raw, 0f, 0f, paint)
                    frame.getPixels(argb, 0, w, 0, 0, w, h)
                    queueFrame(codec, inputIndex(codec, ::drain), argb, w, h, startMs * 1000L)
                    frames++
                    startMs = endMs
                    drain(false)
                }
                if (frames == 0) throw IllegalArgumentException("Figurinha sem frames")
                // Fim do stream: o timestamp do EOS marca a duração do último frame.
                val eos = inputIndex(codec, ::drain)
                codec.queueInputBuffer(eos, 0, 0, startMs * 1000L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                drain(true)
            } finally {
                runCatching { codec.stop() }
                codec.release()
                if (muxing) runCatching { muxer.stop() }
                muxer.release()
                raw.recycle(); frame.recycle()
            }
            return out.readBytes().also { out.delete() }
        }
    }

    /** Espera um buffer de entrada livre, esvaziando a saída enquanto isso (evita travar). */
    private fun inputIndex(codec: MediaCodec, drain: (Boolean) -> Unit): Int {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            val idx = codec.dequeueInputBuffer(TIMEOUT_US)
            if (idx >= 0) return idx
            drain(false)
        }
        throw IllegalStateException("Encoder de vídeo travou")
    }

    /** RGB -> YUV 4:2:0 escrevendo direto nos planos do encoder (funciona em qualquer layout). */
    private fun queueFrame(codec: MediaCodec, idx: Int, argb: IntArray, w: Int, h: Int, ptsUs: Long) {
        val image = codec.getInputImage(idx) ?: throw IllegalStateException("Encoder sem suporte a YUV flexível")
        val (yP, uP, vP) = image.planes
        val yBuf = yP.buffer; val uBuf = uP.buffer; val vBuf = vP.buffer
        for (row in 0 until h) {
            val yRow = row * yP.rowStride
            val src = row * w
            for (col in 0 until w) {
                val c = argb[src + col]
                val r = (c shr 16) and 0xff; val g = (c shr 8) and 0xff; val b = c and 0xff
                yBuf.put(yRow + col * yP.pixelStride, (((66 * r + 129 * g + 25 * b + 128) shr 8) + 16).toByte())
            }
        }
        for (row in 0 until h / 2) {
            val uRow = row * uP.rowStride
            val vRow = row * vP.rowStride
            for (col in 0 until w / 2) {
                // média do bloco 2x2
                var r = 0; var g = 0; var b = 0
                for (dy in 0..1) for (dx in 0..1) {
                    val c = argb[(row * 2 + dy) * w + col * 2 + dx]
                    r += (c shr 16) and 0xff; g += (c shr 8) and 0xff; b += c and 0xff
                }
                r = r shr 2; g = g shr 2; b = b shr 2
                uBuf.put(uRow + col * uP.pixelStride, (((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128).toByte())
                vBuf.put(vRow + col * vP.pixelStride, (((112 * r - 94 * g - 18 * b + 128) shr 8) + 128).toByte())
            }
        }
        codec.queueInputBuffer(idx, 0, w * h * 3 / 2, ptsUs, 0)
    }
}
