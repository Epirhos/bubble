package com.bubble.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import androidx.compose.ui.platform.LocalContext
import com.bubble.app.ui.theme.BubbleColors
import com.bubble.app.vision.AndroidGestureRecognizer
import com.bubble.app.vision.CameraLayer
import com.bubble.shared.core.BubbleState
import com.bubble.shared.ml.VisionEvent
import com.bubble.shared.core.BubbleStateManager
import com.bubble.shared.core.LiveEndReason

/**
 * Point d'entrée du Portail : ouvre la session Live au montage, referme l'activité
 * dès que le moteur repasse en Ambient (fin volontaire, background ou watchdog 120 s).
 * La fermeture de l'activité délie CameraX (cycle de vie) : coupe-circuit caméra.
 */
@Composable
fun LiveRoute(manager: BubbleStateManager, cameraGranted: Boolean, onDismiss: () -> Unit) {
    val state by manager.state.collectAsState()
    val context = LocalContext.current
    val recognizer = remember { AndroidGestureRecognizer(context) }

    LaunchedEffect(Unit) {
        if (manager.state.value !is BubbleState.Live) manager.onPortalOpened()
    }
    LaunchedEffect(state) {
        if (state is BubbleState.Ambient) onDismiss()
    }
    // Pont vision → moteur : seuls des triggers numériques traversent, jamais une frame.
    LaunchedEffect(Unit) {
        recognizer.events.collect { event ->
            when (event) {
                is VisionEvent.GestureDetected -> manager.onLocalGestureDetected(event.gesture)
                VisionEvent.FaceAppeared -> manager.onFaceDetected() // réarme le watchdog 120 s
                VisionEvent.FaceLost -> Unit // le watchdog court, l'échéance fera le reste
            }
        }
    }

    LiveOverlayScreen(
        manager = manager,
        recognizer = recognizer.takeIf { cameraGranted },
        onClose = { manager.onLiveEnded(LiveEndReason.USER_EXIT) },
    )
}

/** Une empreinte de frottement : centre, rayon et instant de naissance (pour la cicatrisation). */
private class RevealStamp(val center: Offset, val radiusPx: Float, val bornAtMillis: Long)

/**
 * L'Overlay Live full-bleed.
 *
 * Composition (du fond vers l'avant) :
 *  1. Flux net simulé (dégradés organiques — la caméra viendra ici).
 *  2. Voile flouté du même flux + teinte givre, percé par le masque de frottement
 *     (voir [FrostLayer] pour le modèle mathématique).
 *  3. Halo d'Aura (présence du partenaire + pulses sur gestes reçus).
 *  4. Une unique pilule de verre (fermer) + rituels de pairage.
 */
@Composable
fun LiveOverlayScreen(
    manager: BubbleStateManager,
    recognizer: AndroidGestureRecognizer?,
    onClose: () -> Unit,
) {
    val stamps = remember { mutableStateListOf<RevealStamp>() }
    var now by remember { mutableLongStateOf(0L) }
    val density = LocalDensity.current
    val brushRadiusPx = with(density) { 96.dp.toPx() }

    // Horloge de cicatrisation : ne tourne que tant que des empreintes vivent.
    LaunchedEffect(stamps.isNotEmpty()) {
        while (stamps.isNotEmpty()) {
            withFrameMillis { frame ->
                now = frame
                stamps.removeAll { frame - it.bornAtMillis > HEAL_TOTAL_MILLIS }
            }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(BubbleColors.Night)
            .pointerInput(Unit) {
                // Tout l'écran est la surface de frottement : aucune zone morte.
                var lastStamp: Offset? = null
                detectDragGestures(
                    onDragStart = { offset ->
                        lastStamp = offset
                        stamps += RevealStamp(offset, brushRadiusPx, frameTime(stamps, now))
                        // Radar : alimente le moteur en coordonnées normalisées (coût négligeable).
                        manager.onRadarFingerMove(offset.x / size.width, offset.y / size.height)
                    },
                    onDrag = { change, _ ->
                        val previous = lastStamp
                        // Ré-échantillonnage : une empreinte tous les rayon/3 parcourus.
                        if (previous == null || (change.position - previous).getDistance() > brushRadiusPx / 3f) {
                            lastStamp = change.position
                            stamps += RevealStamp(change.position, brushRadiusPx, frameTime(stamps, now))
                        }
                        manager.onRadarFingerMove(change.position.x / size.width, change.position.y / size.height)
                        change.consume()
                    },
                )
            },
    ) {
        SimulatedFeed(Modifier.fillMaxSize())
        if (recognizer != null) {
            // Le flux réel s'installe discrètement sous le voile ; il vit et meurt avec l'activité.
            CameraLayer(recognizer = recognizer, modifier = Modifier.fillMaxSize())
        }
        FrostLayer(stamps = stamps, nowMillis = now, modifier = Modifier.fillMaxSize())
        AuraLayer(manager = manager, modifier = Modifier.fillMaxSize())
        PromptWatermark(manager = manager, modifier = Modifier.fillMaxSize())
        CouponReveal(manager = manager, modifier = Modifier.align(Alignment.TopCenter))

        GlassPill(
            onClick = onClose,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 24.dp),
        ) {
            Text("fermer la bulle", color = BubbleColors.Moon.copy(alpha = 0.8f), fontSize = 14.sp)
        }

        PairingRitualOverlay(events = manager.pairingEvents, modifier = Modifier.fillMaxSize())
    }
}

/** Le temps du dernier frame connu (0 au tout premier stamp : il vieillira dès le frame suivant). */
private fun frameTime(stamps: List<RevealStamp>, now: Long): Long =
    if (now != 0L) now else stamps.lastOrNull()?.bornAtMillis ?: 0L

/** Flux net simulé : nappes de dégradés lents, en attendant la caméra. */
@Composable
private fun SimulatedFeed(modifier: Modifier = Modifier) {
    Box(
        modifier.background(
            Brush.radialGradient(
                colors = listOf(BubbleColors.NightHigh, BubbleColors.Night),
                radius = 1400f,
            ),
        ),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            BubbleColors.AuraRose.copy(alpha = 0.10f),
                            Color.Transparent,
                            BubbleColors.AuraWarm.copy(alpha = 0.08f),
                        ),
                    ),
                ),
        )
    }
}

private const val HEAL_PLATEAU_MILLIS = 1_800L
private const val HEAL_FADE_MILLIS = 1_400L
private const val HEAL_TOTAL_MILLIS = HEAL_PLATEAU_MILLIS + HEAL_FADE_MILLIS

/**
 * Le voile de flou percé par le frottement.
 *
 * Modèle : masque de révélation M(p,t) = clamp01( Σᵢ G(‖p−cᵢ‖/r) · H(t−tᵢ) )
 *  - G : atténuation radiale douce (dégradé radial plein centre → bord transparent),
 *  - H : cicatrisation — plateau de 1,8 s puis fondu ease-out sur 1,4 s (le flou "repousse",
 *    fidèle à l'éphémérité du produit),
 *  - opacité du givre en p = givre_de_base × (1 − M).
 *
 * Réalisation GPU : la couche est composée hors écran (CompositingStrategy.Offscreen) ;
 * chaque empreinte est un dégradé radial dessiné en BlendMode.DstOut qui soustrait de
 * l'alpha du voile — la somme et le clamp sont gratuits, faits par le blending.
 */
@Composable
private fun FrostLayer(stamps: List<RevealStamp>, nowMillis: Long, modifier: Modifier = Modifier) {
    Box(modifier.graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)) {
        // Le "flou" : copie assombrie et floutée du monde (blur réel sur API 31+).
        Box(
            Modifier
                .fillMaxSize()
                .blur(28.dp)
                .background(BubbleColors.Night.copy(alpha = 0.86f)),
        )
        Canvas(Modifier.fillMaxSize()) {
            for (stamp in stamps) {
                val strength = healFactor(nowMillis - stamp.bornAtMillis)
                if (strength <= 0f) continue
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = strength),
                            Color.Black.copy(alpha = strength * 0.55f),
                            Color.Transparent,
                        ),
                        center = stamp.center,
                        radius = stamp.radiusPx,
                    ),
                    center = stamp.center,
                    radius = stamp.radiusPx,
                    blendMode = BlendMode.DstOut,
                )
            }
        }
    }
}

/** H(age) : 1 pendant le plateau, puis ease-out quadratique vers 0. */
private fun healFactor(ageMillis: Long): Float = when {
    ageMillis < 0L -> 0f
    ageMillis <= HEAL_PLATEAU_MILLIS -> 1f
    ageMillis >= HEAL_TOTAL_MILLIS -> 0f
    else -> {
        val t = (ageMillis - HEAL_PLATEAU_MILLIS).toFloat() / HEAL_FADE_MILLIS
        (1f - t) * (1f - t)
    }
}

/**
 * Halo d'Aura : lueur chaude persistante proportionnelle à la présence du partenaire,
 * plus un pulse spring à chaque geste reçu (bisou/cœur) — jamais de texte, jamais de badge.
 */
@Composable
private fun BoxScope.AuraLayer(manager: BubbleStateManager, modifier: Modifier = Modifier) {
    val aura by manager.aura.collectAsState()
    val pulse = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        manager.immediateHaptics.collect {
            pulse.snapTo(1f)
            pulse.animateTo(0f, spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessVeryLow))
        }
    }

    // Approximation UI de la décroissance 15 min : l'intensité est relue à chaque recomposition
    // déclenchée par le pulse ou un nouveau signal de présence.
    val presence = aura.lastPresenceAt?.let { 0.5f } ?: 0f
    val intensity = (presence + pulse.value).coerceIn(0f, 1f)

    if (intensity > 0.01f) {
        Canvas(modifier) {
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color.Transparent,
                        BubbleColors.AuraWarm.copy(alpha = lerp(0f, 0.35f, intensity)),
                    ),
                    center = center,
                    radius = size.maxDimension * 0.75f,
                ),
            )
        }
    }
}
