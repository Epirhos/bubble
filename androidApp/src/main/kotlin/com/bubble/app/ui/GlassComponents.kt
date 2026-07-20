package com.bubble.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bubble.app.ui.theme.BubbleColors

/**
 * Pilule de verre dépoli : la seule forme de contrôle autorisée par le contrat UI
 * (aucune barre, aucun bouton opaque). Fond translucide + reflet vertical + liseré
 * blanc 15 % + halo optionnel — le "blur backdrop" réel viendra avec RenderEffect
 * (API 31+) quand un vrai flux vivra derrière ; l'illusion de verre est déjà là.
 */
@Composable
fun GlassPill(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    glowColor: Color = Color.Transparent,
    contentPadding: PaddingValues = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
    content: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .glassGlow(glowColor)
            .clip(CircleShape)
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.14f), // reflet haut
                        BubbleColors.GlassFill,
                        Color.White.copy(alpha = 0.04f),
                    ),
                ),
            )
            .border(1.dp, BubbleColors.GlassStroke, CircleShape)
            .then(
                if (onClick != null) {
                    Modifier.clickable(interactionSource = interaction, indication = null, onClick = onClick)
                } else {
                    Modifier
                },
            )
            .padding(contentPadding),
    ) {
        content()
    }
}

/**
 * Halo lumineux doux derrière un élément — le langage visuel de Bubble : jamais de
 * texte d'état, une lueur. Dessiné hors layout (drawBehind), n'affecte pas la taille.
 */
fun Modifier.glassGlow(color: Color, radius: Dp = 36.dp): Modifier =
    if (color == Color.Transparent) {
        this
    } else {
        drawBehind {
            val r = radius.toPx() + size.maxDimension / 2f
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(color.copy(alpha = 0.45f), Color.Transparent),
                    center = Offset(size.width / 2f, size.height / 2f),
                    radius = r,
                ),
                radius = r,
                center = Offset(size.width / 2f, size.height / 2f),
            )
        }
    }
