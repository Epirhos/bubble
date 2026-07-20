package com.bubble.app

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.bubble.app.pairing.PairingController
import com.bubble.app.pairing.PairingScreen
import com.bubble.app.ui.LiveRoute
import com.bubble.app.ui.theme.BubbleTheme
import com.bubble.shared.core.BubbleState

class MainActivity : ComponentActivity() {

    private val cameraGranted = mutableStateOf(false)
    private val requestCamera =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            cameraGranted.value = granted
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)

        val app = application as BubbleApplication
        val manager = app.stateManager

        cameraGranted.value = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        if (!cameraGranted.value) requestCamera.launch(Manifest.permission.CAMERA)

        setContent {
            BubbleTheme {
                val state by manager.state.collectAsState()
                if (state is BubbleState.Unpaired) {
                    val controller = remember {
                        PairingController(
                            scope = lifecycleScope,
                            manager = manager,
                            // Signaling partagé : le lien P2P réutilise cette connexion après pairage.
                            signaling = app.sessionCoordinator.signaling,
                            onPaired = app::onPairingComplete,
                        )
                    }
                    PairingScreen(controller = controller)
                } else {
                    val granted by cameraGranted
                    LiveRoute(
                        manager = manager,
                        cameraGranted = granted,
                        onDismiss = { finish() },
                    )
                }
            }
        }
    }
}
