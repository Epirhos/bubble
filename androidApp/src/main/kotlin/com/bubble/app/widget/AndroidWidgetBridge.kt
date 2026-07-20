package com.bubble.app.widget

import android.content.Context
import androidx.glance.appwidget.updateAll
import com.bubble.shared.widget.WidgetBridge

/**
 * Implémentation Android du [WidgetBridge] : persiste l'état dans [WidgetStorage] puis
 * demande à Glance de recomposer le widget. Le débit est déjà limité en amont par le
 * WidgetRefreshPipeline (commonMain) — ici, écriture + reload, rien de plus.
 */
class AndroidWidgetBridge(private val context: Context) : WidgetBridge {

    override suspend fun writeSnapshot(bytes: ByteArray, receivedAtMillis: Long) {
        WidgetStorage.writeSnapshot(context, bytes)
        BubblePortalWidget().updateAll(context)
    }

    override suspend fun writeAura(intensity: Float) {
        WidgetStorage.writeAura(context, intensity)
        BubblePortalWidget().updateAll(context)
    }
}
