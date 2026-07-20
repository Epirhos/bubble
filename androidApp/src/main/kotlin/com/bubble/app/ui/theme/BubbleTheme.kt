package com.bubble.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Palette "calm" : nuit profonde légèrement violacée, accents chauds pour l'Aura. */
object BubbleColors {
    val Night = Color(0xFF0A0A0F)
    val NightHigh = Color(0xFF14121E)
    val AuraWarm = Color(0xFFFFB27A)
    val AuraRose = Color(0xFFF48FB1)
    val GlassStroke = Color(0x26FFFFFF) // blanc 15 %
    val GlassFill = Color(0x14FFFFFF) // blanc 8 %
    val Moon = Color(0xFFE8E6F0)
}

/** Dark natif obligatoire : aucune variante claire n'existe. */
@Composable
fun BubbleTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = BubbleColors.Night,
            surface = BubbleColors.NightHigh,
            primary = BubbleColors.AuraWarm,
            onBackground = BubbleColors.Moon,
            onSurface = BubbleColors.Moon,
        ),
        content = content,
    )
}
