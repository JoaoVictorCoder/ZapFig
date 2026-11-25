package com.zapfig.app.media

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

/**
 * Tira o som de um vídeo sem recodificar: copia a trilha AAC para um M4A,
 * cortando nos primeiros [maxSeconds] (mesma duração da figurinha animada).
 */
object AudioExtractor {

    /** Devolve os bytes do M4A, ou um array vazio se o vídeo não tiver som. */
    fun fromVideo(mp4: ByteArray, maxSeconds: Int, cacheDir: File): ByteArray {
        val input = File.createTempFile("snd", ".mp4", cacheDir)
        val output = File.createTempFile("snd", ".m4a", cacheDir)
        val extractor = MediaExtractor()
        try {
            input.writeBytes(mp4)
            extractor.setDataSource(input.absolutePath)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return ByteArray(0)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME)
            if (mime != MediaFormat.MIMETYPE_AUDIO_AAC) {
                throw IllegalArgumentException("formato de áudio não suportado ($mime)")
            }
            extractor.selectTrack(track)

            val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val outTrack = muxer.addTrack(format)
            muxer.start()
            val maxUs = maxSeconds * 1_000_000L
            val buf = ByteBuffer.allocate(256 * 1024)
            val info = MediaCodec.BufferInfo()
            var samples = 0
            while (true) {
                info.offset = 0
                info.size = extractor.readSampleData(buf, 0)
                if (info.size < 0) break
                info.presentationTimeUs = extractor.sampleTime
                if (info.presentationTimeUs > maxUs) break
                @Suppress("WrongConstant")
                info.flags = extractor.sampleFlags
                muxer.writeSampleData(outTrack, buf, info)
                samples++
                extractor.advance()
            }
            muxer.stop()
            muxer.release()
            if (samples == 0) return ByteArray(0)
            return output.readBytes()
        } finally {
            extractor.release()
            input.delete()
            output.delete()
        }
    }
}
