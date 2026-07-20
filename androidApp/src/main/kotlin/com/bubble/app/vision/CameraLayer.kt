package com.bubble.app.vision

import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * Le flux caméra frontal, discret sous le voile de flou de l'Overlay.
 *
 * Lié au cycle de vie : quand l'activité se ferme (état Ambient, watchdog 120 s,
 * background), CameraX coupe la session — c'est le coupe-circuit matériel.
 * L'analyse tourne sur le thread d'inférence du recognizer, jamais sur le Main Thread ;
 * STRATEGY_KEEP_ONLY_LATEST jette tout backlog de frames (frame dropping structurel).
 */
@Composable
fun CameraLayer(recognizer: AndroidGestureRecognizer, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember {
        PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
    }

    AndroidView(factory = { previewView }, modifier = modifier)

    DisposableEffect(Unit) {
        recognizer.start()
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener(
            {
                val provider = providerFuture.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()
                    .also { it.setAnalyzer(recognizer.executor, recognizer.analyzer) }
                provider.unbindAll()
                provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_FRONT_CAMERA,
                    preview,
                    analysis,
                )
            },
            ContextCompat.getMainExecutor(context),
        )
        onDispose {
            recognizer.stop()
            runCatching { providerFuture.get().unbindAll() }
        }
    }
}
