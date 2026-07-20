package com.bubble.app.widget

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.ImageProvider
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.ContentScale
import androidx.glance.layout.fillMaxSize
import androidx.glance.Image
import androidx.glance.action.clickable
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.text.Text
import com.bubble.app.MainActivity
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider

/**
 * Portail passif sur l'écran d'accueil : dernier snapshot flouté + halo d'Aura, et un
 * unique deep link `bubble://live` qui ouvre l'app sur [LiveOverlayScreen] au toucher.
 *
 * Aucun traitement lourd (contrainte OS) : lecture de deux fichiers, rendu, terminé.
 * Aucun geste de frottement ici — le défloutage se fait après ouverture de l'app.
 */
class BubblePortalWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val snapshot = WidgetStorage.snapshotFile(context)
            .takeIf { it.exists() }
            ?.let { runCatching { BitmapFactory.decodeFile(it.absolutePath) }.getOrNull() }
        val auraIntensity = WidgetStorage.readAura(context)

        provideContent {
            // Intent explicite vers MainActivity, portant le deep link bubble://live.
            val launchLive = Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                data = Uri.parse("bubble://live")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            Box(
                modifier = GlanceModifier
                    .fillMaxSize()
                    .cornerRadius(24.dp)
                    .background(ColorProvider(NIGHT))
                    .clickable(actionStartActivity(launchLive)),
                contentAlignment = Alignment.Center,
            ) {
                if (snapshot != null) {
                    Image(
                        provider = ImageProvider(snapshot),
                        contentDescription = "Portail",
                        contentScale = ContentScale.Crop,
                        modifier = GlanceModifier.fillMaxSize(),
                    )
                } else {
                    Text(
                        "· ·",
                        style = TextStyle(color = ColorProvider(Color(0x66FFFFFF))),
                    )
                }
                // Halo d'Aura : voile chaud dont l'opacité suit la présence du partenaire.
                if (auraIntensity > 0.01f) {
                    Box(
                        modifier = GlanceModifier
                            .fillMaxSize()
                            .background(ColorProvider(auraOverlay(auraIntensity))),
                    ) {}
                }
            }
        }
    }

    private fun auraOverlay(intensity: Float): Color {
        val alpha = (intensity * 0.4f).coerceIn(0f, 0.4f)
        return Color(1f, 0.70f, 0.48f, alpha) // AuraWarm translucide
    }

    private companion object {
        val NIGHT = Color(0xFF0A0A0F)
    }
}

/** Receiver déclaré au manifest ; instancie le widget pour le système. */
class BubblePortalWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = BubblePortalWidget()
}
