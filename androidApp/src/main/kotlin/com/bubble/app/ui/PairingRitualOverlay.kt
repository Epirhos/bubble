package com.bubble.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.util.lerp
import com.bubble.app.ui.theme.BubbleColors
import com.bubble.shared.core.PairingEvent
import kotlinx.coroutines.flow.SharedFlow

private enum class Ritual { MERGE, SPLIT }

/**
 * Rituels du lien (invariant produit : lien strictement 1:1, rupture obligatoire avant
 * un nouveau lien — appliqué par le moteur, chorégraphié ici) :
 *  - [PairingEvent.BubblesMerged] : deux bulles glissent l'une vers l'autre (spring avec
 *    léger rebond) et fusionnent en une seule dans un halo.
 *  - [PairingEvent.BubbleSplit] : la bulle unique se scinde, les deux moitiés s'éloignent
 *    et s'éteignent en fondu.
 *
 * `progress` 0→1 pilote toute la scène ; aucune étape discrète, uniquement des interpolations.
 */
@Composable
fun PairingRitualOverlay(events: SharedFlow<PairingEvent>, modifier: Modifier = Modifier) {
    var ritual by remember { mutableStateOf<Ritual?>(null) }
    val progress = remember { Animatable(0f) }
    val fade = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        events.collect { event ->
            ritual = when (event) {
                is PairingEvent.BubblesMerged -> Ritual.MERGE
                is PairingEvent.BubbleSplit -> Ritual.SPLIT
            }
            progress.snapTo(0f)
            fade.snapTo(1f)
            progress.animateTo(
                1f,
                spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessLow),
            )
            fade.animateTo(0f, tween(durationMillis = 900))
            ritual = null
        }
    }

    val current = ritual ?: return
    Canvas(modifier.fillMaxSize()) {
        val t = progress.value
        val alpha = fade.value
        val radius = size.minDimension * 0.11f
        val centerY = size.height * 0.42f
        val apart = size.width * 0.30f

        // MERGE : distance apart→0 ; SPLIT : 0→apart (même scène, temps inversé).
        val distance = if (current == Ritual.MERGE) lerp(apart, 0f, t) else lerp(0f, apart, t)
        val left = Offset(size.width / 2f - distance, centerY)
        val right = Offset(size.width / 2f + distance, centerY)

        // Halo de fusion : culmine quand les bulles se touchent.
        val closeness = 1f - (distance / apart).coerceIn(0f, 1f)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(BubbleColors.AuraWarm.copy(alpha = 0.35f * closeness * alpha), Color.Transparent),
                center = Offset(size.width / 2f, centerY),
                radius = radius * 3.2f,
            ),
            radius = radius * 3.2f,
            center = Offset(size.width / 2f, centerY),
        )

        for ((bubbleCenter, tint) in listOf(left to BubbleColors.AuraRose, right to BubbleColors.AuraWarm)) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.55f * alpha),
                        tint.copy(alpha = 0.30f * alpha),
                        Color.Transparent,
                    ),
                    center = bubbleCenter,
                    radius = radius,
                ),
                radius = radius,
                center = bubbleCenter,
            )
        }
    }
}
