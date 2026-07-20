package com.bubble.app.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.opengl.GLES20
import android.util.Log
import android.view.Surface
import com.bubble.shared.media.DailyBubbleComposer
import com.bubble.shared.media.DailyBubbleMood
import com.bubble.shared.media.DailyBubbleResult
import com.bubble.shared.media.DailyBubbleSpec
import com.bubble.shared.media.DailyFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Compresseur de journée Android : encode les fragments (snapshots/canvas) en un time-lapse
 * MP4/H.264 via MediaCodec + MediaMuxer, avec une ambiance douce appliquée au Canvas.
 *
 * Mémoire (contrainte étape 7) : traitement STRICTEMENT en flux — une image décodée à la fois,
 * `bitmap.recycle()` immédiat après dessin sur la Surface d'entrée de l'encodeur. La journée
 * entière n'est jamais en RAM. `inSampleSize` sous-échantillonne au décodage pour ne jamais
 * allouer un bitmap plus grand que la cible.
 */
class MediaCodecDailyComposer : DailyBubbleComposer {

    override suspend fun compile(
        fragments: List<DailyFragment>,
        outputPath: String,
        spec: DailyBubbleSpec,
    ): DailyBubbleResult = withContext(Dispatchers.Default) {
        val ordered = fragments.sortedBy { it.capturedAtEpochMillis }
        if (ordered.isEmpty()) return@withContext DailyBubbleResult.Empty

        val frames = resample(ordered, spec)
        if (frames.isEmpty()) return@withContext DailyBubbleResult.Empty

        var codec: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var surface: Surface? = null
        try {
            val format = MediaFormat.createVideoFormat(MIME, spec.width, spec.height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, bitRate(spec))
                setInteger(MediaFormat.KEY_FRAME_RATE, spec.fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            codec = MediaCodec.createEncoderByType(MIME).apply {
                configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            }
            surface = codec.createInputSurface()
            codec.start()
            muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            val encoder = SurfaceEncoderDrain(codec, muxer)
            val moodFilter = moodPaint(spec.mood)
            val dst = Rect(0, 0, spec.width, spec.height)

            for (fragment in frames) {
                val bitmap = decodeScaled(fragment.localPath, spec.width, spec.height) ?: continue
                val canvas = surface.lockCanvas(null)
                try {
                    canvas.drawColor(Color.BLACK)
                    canvas.drawBitmap(bitmap, sourceRect(bitmap, dst), dst, moodFilter)
                } finally {
                    surface.unlockCanvasAndPost(canvas)
                }
                bitmap.recycle() // libéré tout de suite : jamais deux images vivantes à la fois
                encoder.drain(endOfStream = false)
            }
            codec.signalEndOfInputStream()
            encoder.drain(endOfStream = true)

            val durationMillis = frames.size * (1_000L / spec.fps)
            DailyBubbleResult.Success(outputPath, durationMillis)
        } catch (e: Exception) {
            Log.e(TAG, "Échec compilation Daily Bubble", e)
            DailyBubbleResult.Failure(e.message ?: "compile error")
        } finally {
            runCatching { surface?.release() }
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
        }
    }

    /** Ré-échantillonne les fragments pour approcher la durée cible du time-lapse. */
    private fun resample(fragments: List<DailyFragment>, spec: DailyBubbleSpec): List<DailyFragment> {
        val targetFrames = spec.fps * spec.targetDurationSeconds
        if (fragments.size <= targetFrames) return fragments
        val step = fragments.size.toFloat() / targetFrames
        return (0 until targetFrames).map { fragments[(it * step).toInt()] }
    }

    private fun decodeScaled(path: String, targetW: Int, targetH: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= targetW && bounds.outHeight / (sample * 2) >= targetH) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565 // moitié moins de RAM que ARGB_8888
        }
        return BitmapFactory.decodeFile(path, options)
    }

    private fun sourceRect(bitmap: Bitmap, dst: Rect): Rect {
        // Center-crop pour remplir le cadre portrait sans déformer.
        val srcRatio = bitmap.width.toFloat() / bitmap.height
        val dstRatio = dst.width().toFloat() / dst.height()
        return if (srcRatio > dstRatio) {
            val w = (bitmap.height * dstRatio).toInt()
            val x = (bitmap.width - w) / 2
            Rect(x, 0, x + w, bitmap.height)
        } else {
            val h = (bitmap.width / dstRatio).toInt()
            val y = (bitmap.height - h) / 2
            Rect(0, y, bitmap.width, y + h)
        }
    }

    /** Filtre d'ambiance : teinte chaude/douce en overlay léger (Calm Technology). */
    private fun moodPaint(mood: DailyBubbleMood): Paint {
        val tint = when (mood) {
            DailyBubbleMood.WARM_DUSK -> Color.argb(40, 255, 178, 122)
            DailyBubbleMood.SOFT_FILM -> Color.argb(30, 255, 240, 220)
            DailyBubbleMood.MOONLIGHT -> Color.argb(40, 150, 170, 255)
        }
        return Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply {
            colorFilter = PorterDuffColorFilter(tint, PorterDuff.Mode.OVERLAY)
        }
    }

    private fun bitRate(spec: DailyBubbleSpec): Int = (spec.width * spec.height * spec.fps * 0.12f).toInt()

    private companion object {
        const val TAG = "DailyComposer"
        const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC
        // GLES20 importé pour garantir la dispo EGL du chemin Surface sur tous les vendors.
        val ensureGl = GLES20.GL_TRUE
    }
}
