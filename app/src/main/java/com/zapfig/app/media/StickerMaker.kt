package com.zapfig.app.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer

enum class CropMode { FIT, CROP, STRETCH }

/** Toda a conversão de mídia do app. Chamada pelo motor Go via Callback.convert. */
class StickerMaker(private val ctx: Context) {

    companion object {
        const val SIZE = 512
        const val MAX_STATIC = 100 * 1024
        const val MAX_ANIMATED = 500 * 1024
        const val MAX_SECONDS = 8
    }

    data class Options(val crop: CropMode, val fps: Int, val maxSeconds: Int)

    // ---------- foto -> figurinha ----------

    fun image(input: ByteArray, opt: Options): ByteArray {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(input, 0, input.size, bounds)
        if (bounds.outWidth <= 0) throw IllegalArgumentException("Imagem inválida")
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= SIZE && bounds.outHeight / (sample * 2) >= SIZE) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(input, 0, input.size,
            BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw IllegalArgumentException("Não consegui ler a imagem")
        val src = fixRotation(decoded, input)
        val canvas = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        drawInto(canvas, src, opt.crop)
        return encodeStatic(canvas)
    }

    // ---------- vídeo/GIF -> figurinha animada ----------

    fun video(input: ByteArray, opt: Options): ByteArray {
        val tmp = File.createTempFile("vid", ".mp4", ctx.cacheDir)
        val frames = ArrayList<Bitmap>()
        try {
            tmp.writeBytes(input)
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(tmp.absolutePath)
                val durMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                if (durMs <= 0) throw IllegalArgumentException("Vídeo sem duração")
                val useMs = minOf(durMs, opt.maxSeconds * 1000L)
                val stepMs = 1000L / opt.fps
                var t = 0L
                while (t < useMs) {
                    val f = r.getScaledFrameAtTime(t * 1000, MediaMetadataRetriever.OPTION_CLOSEST, SIZE * 2, SIZE * 2)
                    if (f != null) {
                        val c = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
                        drawInto(c, f, opt.crop)
                        f.recycle()
                        frames += c
                    }
                    t += stepMs
                }
            } finally {
                r.release()
            }
            if (frames.isEmpty()) throw IllegalArgumentException("Não consegui ler os frames do vídeo")

            // Tenta qualidades decrescentes; se ainda passar de 500 KB, descarta metade dos frames.
            var list: List<Bitmap> = frames
            var frameMs = (1000 / opt.fps)
            var last: ByteArray? = null
            repeat(3) {
                for (q in floatArrayOf(65f, 42f, 25f)) {
                    val out = encodeAnimated(list, frameMs, q)
                    last = out
                    if (out.size <= MAX_ANIMATED) return out
                }
                list = list.filterIndexed { i, _ -> i % 2 == 0 }
                frameMs *= 2
            }
            return last ?: throw IllegalStateException("Falha ao codificar")
        } finally {
            frames.forEach { it.recycle() }
            tmp.delete()
        }
    }

    private fun encodeAnimated(frames: List<Bitmap>, frameMs: Int, quality: Float): ByteArray =
        WebPAnim(SIZE, SIZE).use { enc ->
            frames.forEachIndexed { i, f -> enc.addFrame(f, i * frameMs, quality) }
            enc.assemble(frames.size * frameMs)
        }

    // ---------- texto -> figurinha ----------

    fun text(text: String): ByteArray {
        val bmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val pad = 36
        val width = SIZE - pad * 2
        val fill = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        // Maior fonte que cabe no quadrado.
        var size = 160f
        var layout: StaticLayout
        while (true) {
            fill.textSize = size
            layout = StaticLayout.Builder.obtain(text, 0, text.length, fill, width)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setLineSpacing(0f, 0.95f)
                .build()
            val longestWord = text.split(Regex("\\s+")).maxOfOrNull { fill.measureText(it) } ?: 0f
            if ((layout.height <= SIZE - pad * 2 && longestWord <= width) || size <= 28f) break
            size -= 6f
        }
        val stroke = TextPaint(fill).apply {
            style = Paint.Style.STROKE
            strokeWidth = size / 7f
            strokeJoin = Paint.Join.ROUND
            color = Color.BLACK
        }
        val strokeLayout = StaticLayout.Builder.obtain(text, 0, text.length, stroke, width)
            .setAlignment(Layout.Alignment.ALIGN_CENTER).setLineSpacing(0f, 0.95f).build()
        canvas.save()
        canvas.translate(pad.toFloat(), (SIZE - layout.height) / 2f)
        strokeLayout.draw(canvas)
        layout.draw(canvas)
        canvas.restore()
        return encodeStatic(bmp)
    }

    // ---------- figurinha -> imagem ----------

    fun toImage(webp: ByteArray): ByteArray {
        val bmp = if (Build.VERSION.SDK_INT >= 28) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(webp))) { d, _, _ ->
                d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            BitmapFactory.decodeByteArray(webp, 0, webp.size)
        } ?: throw IllegalArgumentException("Figurinha inválida")
        val bos = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, bos)
        return bos.toByteArray()
    }

    // ---------- utilidades ----------

    private fun drawInto(dst: Bitmap, src: Bitmap, mode: CropMode) {
        dst.eraseColor(Color.TRANSPARENT)
        val c = Canvas(dst)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        val full = RectF(0f, 0f, SIZE.toFloat(), SIZE.toFloat())
        when (mode) {
            CropMode.STRETCH -> c.drawBitmap(src, null, full, paint)
            CropMode.CROP -> {
                val side = minOf(src.width, src.height)
                val l = (src.width - side) / 2
                val t = (src.height - side) / 2
                c.drawBitmap(src, Rect(l, t, l + side, t + side), full, paint)
            }
            CropMode.FIT -> {
                val s = minOf(SIZE.toFloat() / src.width, SIZE.toFloat() / src.height)
                val w = src.width * s
                val h = src.height * s
                c.drawBitmap(src, null, RectF((SIZE - w) / 2, (SIZE - h) / 2, (SIZE + w) / 2, (SIZE + h) / 2), paint)
            }
        }
    }

    private fun encodeStatic(bmp: Bitmap): ByteArray {
        @Suppress("DEPRECATION")
        val format = if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
        var quality = 92
        while (true) {
            val bos = ByteArrayOutputStream()
            bmp.compress(format, quality, bos)
            if (bos.size() <= MAX_STATIC || quality <= 20) return bos.toByteArray()
            quality -= 8
        }
    }

    private fun fixRotation(bmp: Bitmap, input: ByteArray): Bitmap {
        val orientation = try {
            ExifInterface(ByteArrayInputStream(input))
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } catch (e: Exception) { ExifInterface.ORIENTATION_NORMAL }
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            else -> return bmp
        }
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
    }
}
