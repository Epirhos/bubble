package com.bubble.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bubble.app.ui.theme.BubbleColors
import com.bubble.shared.core.BubbleStateManager

/**
 * Amorce de jeu en filigrane GRAVÉ dans le verre (contrainte : pas un pop-up bloquant).
 * Un léger flou + faible opacité donnent l'effet d'une inscription dans le givre ; les
 * partenaires écrivent/dessinent par-dessus (le Canvas gère déjà le tracé et l'écrasement).
 */
@Composable
fun PromptWatermark(manager: BubbleStateManager, modifier: Modifier = Modifier) {
    val prompt by manager.activePrompt.collectAsState()

    AnimatedVisibility(
        visible = prompt != null,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier,
    ) {
        Box(Modifier, contentAlignment = Alignment.Center) {
            Text(
                text = prompt?.text.orEmpty(),
                color = BubbleColors.Moon.copy(alpha = 0.28f), // gravure discrète, jamais dominante
                fontSize = 26.sp,
                fontWeight = FontWeight.Light,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .padding(horizontal = 40.dp)
                    .blur(0.7.dp),
            )
        }
    }
}

/**
 * Révélation d'un coupon (mini-défi) : pilule de verre en haut. Au tap, il s'évapore
 * (onCouponRead) — éphémère, jamais d'historique. Session courte : rien ne force à rester.
 */
@Composable
fun CouponReveal(manager: BubbleStateManager, modifier: Modifier = Modifier) {
    val coupon by manager.activeCoupon.collectAsState()

    AnimatedVisibility(
        visible = coupon != null,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier.statusBarsPadding().padding(top = 20.dp),
    ) {
        GlassPill(
            onClick = { manager.onCouponRead() },
            glowColor = BubbleColors.AuraWarm,
        ) {
            Text(
                text = coupon?.text.orEmpty(),
                color = BubbleColors.Moon,
                fontSize = 14.sp,
            )
        }
    }
}
