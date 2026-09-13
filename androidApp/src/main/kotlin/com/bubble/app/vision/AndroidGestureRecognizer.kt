package com.bubble.app.vision

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.bubble.shared.ml.AdaptiveSamplingPolicy
import com.bubble.shared.ml.GestureDebouncer
import com.bubble.shared.ml.GestureRecognizer
import com.bubble.shared.ml.GestureTriggers
import com.bubble.shared.ml.Landmark
import com.bubble.shared.ml.VisionEvent
import com.bubble.shared.signal.HapticGesture
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.ImageProcessingOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Implémentation Android du [GestureRecognizer] : MediaPipe HandLandmarker + FaceLandmarker.
 *
 * Cyber-hygiène (contrat strict) :
 *  - Toute l'inférence tourne sur [executor] (thread unique dédié) — jamais sur le Main Thread.
 *  - Chaque frame est détruite immédiatement après inférence (`bitmap.recycle()` +
 *    `imageProxy.close()` en finally). Aucune frame n'est stockée ni transmise :
 *    seuls des [VisionEvent] (triggers numériques) sortent de cette classe.
 *  - Frame dropping à deux étages : STRATEGY_KEEP_ONLY_LATEST côté CameraX (le backlog
 *    est jeté), + cadence adaptative pilotée par [AdaptiveSamplingPolicy] (commonMain) :
 *    IDLE 2 Hz visage seul (sonde mains 1×/s) ↔ ACTIVE ~7 Hz visage+mains.
 *
 * Les décisions géométriques (cœur/bisou) et le debouncing viennent de commonMain
 * ([GestureTriggers]/[GestureDebouncer]) — identiques à iOS, testés sur JVM.
 */
class AndroidGestureRecognizer(private val context: Context) : GestureRecognizer {

    private val _events = MutableSharedFlow<VisionEvent>(extraBufferCapacity = 16)
    override val events: Flow<VisionEvent> = _events.asSharedFlow()

    /** Thread unique d'inférence, aussi utilisé comme executor de l'ImageAnalysis. */
    val executor: ExecutorService = Executors.newSingleThreadExecutor()

    val analyzer: ImageAnalysis.Analyzer = ImageAnalysis.Analyzer { image -> process(image) }

    private var handLandmarker: HandLandmarker? = null
    private var faceLandmarker: FaceLandmarker? = null
    private val debouncer = GestureDebouncer(holdFrames = 3, cooldownMillis = 3_000)
    private val sampling = AdaptiveSamplingPolicy()
    private var faceVisible = false

    override fun start() {
        executor.execute {
            if (handLandmarker != null) return@execute
            try {
                handLandmarker = HandLandmarker.createFromOptions(
                    context,
                    HandLandmarker.HandLandmarkerOptions.builder()
                        .setBaseOptions(BaseOptions.builder().setModelAssetPath(HAND_MODEL).build())
                        .setRunningMode(RunningMode.IMAGE)
                        .setNumHands(2)
                        .build(),
                )
                faceLandmarker = FaceLandmarker.createFromOptions(
                    context,
                    FaceLandmarker.FaceLandmarkerOptions.builder()
                        .setBaseOptions(BaseOptions.builder().setModelAssetPath(FACE_MODEL).build())
                        .setRunningMode(RunningMode.IMAGE)
                        .setNumFaces(1)
                        .build(),
                )
            } catch (e: Throwable) {
                // Throwable et non Exception : quand l'ABI de l'appareil n'a pas de
                // libmediapipe_tasks_vision_jni.so (cas des émulateurs x86_64), le chargement
                // natif lève un UnsatisfiedLinkError, qui est un Error et non une Exception.
                // Un catch (Exception) le laissait filer ; comme on est dans executor.execute,
                // il remontait à l'UncaughtExceptionHandler et tuait l'application entière.
                // La vision est une couche optionnelle : son absence doit dégrader Bubble,
                // jamais l'arrêter — le pairage et le lien E2EE n'en dépendent pas.
                Log.w(TAG, "Vision indisponible (modèles ou bibliothèque native) — désactivée", e)
                handLandmarker = null
                faceLandmarker = null
            }
        }
    }

    override fun stop() {
        executor.execute {
            handLandmarker?.close()
            handLandmarker = null
            faceLandmarker?.close()
            faceLandmarker = null
            faceVisible = false
        }
    }

    private fun process(image: ImageProxy) {
        try {
            val face = faceLandmarker ?: return
            val hand = handLandmarker ?: return
            val now = SystemClock.uptimeMillis()
            // Cadence adaptative : IDLE 2 Hz (visage seul) ↔ ACTIVE ~7 Hz (visage + mains).
            val plan = sampling.planFrame(now) ?: return

            val bitmap = image.toBitmap()
            val mpImage = BitmapImageBuilder(bitmap).build()
            val options = ImageProcessingOptions.builder()
                .setRotationDegrees(image.imageInfo.rotationDegrees)
                .build()
            val faceResult = face.detect(mpImage, options)
            val hands = if (plan.runHandModel) hand.detect(mpImage, options).landmarks() else emptyList()
            bitmap.recycle() // la frame ne survit jamais à l'inférence

            if (plan.runHandModel) sampling.onHandsResult(hands.isNotEmpty(), now)

            val faceLm = faceResult.faceLandmarks().firstOrNull()
            emitFacePresence(faceLm != null)

            val candidate = detectCandidate(faceLm, hands)
            debouncer.onFrame(candidate, now)?.let { confirmed ->
                _events.tryEmit(VisionEvent.GestureDetected(confirmed))
            }
        } catch (e: Exception) {
            Log.w(TAG, "Inférence ignorée", e)
        } finally {
            image.close()
        }
    }

    private fun emitFacePresence(hasFace: Boolean) {
        if (hasFace) {
            // Ré-émis à chaque frame traité : chaque émission réarme le watchdog 120 s.
            _events.tryEmit(VisionEvent.FaceAppeared)
            faceVisible = true
        } else if (faceVisible) {
            faceVisible = false
            _events.tryEmit(VisionEvent.FaceLost)
        }
    }

    private fun detectCandidate(
        faceLm: List<NormalizedLandmark>?,
        hands: List<List<NormalizedLandmark>>,
    ): HapticGesture? {
        if (hands.size >= 2) {
            val a = hands[0]
            val b = hands[1]
            if (GestureTriggers.isHeart(
                    thumbA = a[THUMB_TIP].toShared(), indexA = a[INDEX_TIP].toShared(),
                    thumbB = b[THUMB_TIP].toShared(), indexB = b[INDEX_TIP].toShared(),
                )
            ) {
                return HapticGesture.HEART
            }
        }
        if (faceLm != null && hands.isNotEmpty()) {
            val upperLip = faceLm[LIP_UPPER].toShared()
            val lowerLip = faceLm[LIP_LOWER].toShared()
            val mouthCenter = Landmark((upperLip.x + lowerLip.x) / 2f, (upperLip.y + lowerLip.y) / 2f)
            val isKiss = GestureTriggers.isKiss(
                mouthLeft = faceLm[MOUTH_LEFT].toShared(),
                mouthRight = faceLm[MOUTH_RIGHT].toShared(),
                cheekLeft = faceLm[CHEEK_LEFT].toShared(),
                cheekRight = faceLm[CHEEK_RIGHT].toShared(),
                mouthCenter = mouthCenter,
                handPoints = hands.flatten().map { it.toShared() },
            )
            if (isKiss) return HapticGesture.KISS
        }
        return null
    }

    private fun NormalizedLandmark.toShared() = Landmark(x(), y())

    private companion object {
        const val TAG = "BubbleVision"
        const val HAND_MODEL = "hand_landmarker.task"
        const val FACE_MODEL = "face_landmarker.task"

        // Indices MediaPipe : mains (21 points) et face mesh (478 points).
        const val THUMB_TIP = 4
        const val INDEX_TIP = 8
        const val LIP_UPPER = 13
        const val LIP_LOWER = 14
        const val MOUTH_LEFT = 61
        const val MOUTH_RIGHT = 291
        const val CHEEK_LEFT = 234
        const val CHEEK_RIGHT = 454
    }
}
