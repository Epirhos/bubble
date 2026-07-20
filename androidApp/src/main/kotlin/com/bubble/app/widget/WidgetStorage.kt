package com.bubble.app.widget

import android.content.Context
import java.io.File

/**
 * Stockage local partagé entre le pipeline (écriture) et le widget Glance (lecture).
 * Fichiers simples dans filesDir — lisibles instantanément par le widget sans traitement,
 * sans base de données (contrainte de légèreté : ne jamais faire chauffer / tuer le widget).
 */
object WidgetStorage {
    private fun dir(context: Context): File =
        File(context.filesDir, "widget").apply { mkdirs() }

    fun snapshotFile(context: Context): File = File(dir(context), "portal_snapshot.jpg")

    private fun auraFile(context: Context): File = File(dir(context), "aura")

    fun writeAura(context: Context, intensity: Float) {
        auraFile(context).writeText(intensity.coerceIn(0f, 1f).toString())
    }

    fun readAura(context: Context): Float =
        auraFile(context).takeIf { it.exists() }?.readText()?.toFloatOrNull()?.coerceIn(0f, 1f) ?: 0f

    /** Écriture atomique du snapshot (tmp + rename) pour ne jamais exposer un fichier partiel. */
    fun writeSnapshot(context: Context, bytes: ByteArray) {
        val target = snapshotFile(context)
        val tmp = File(target.parentFile, "portal_snapshot.tmp")
        tmp.writeBytes(bytes)
        if (target.exists()) target.delete()
        tmp.renameTo(target)
    }

    fun clear(context: Context) {
        snapshotFile(context).delete()
        auraFile(context).delete()
    }
}
