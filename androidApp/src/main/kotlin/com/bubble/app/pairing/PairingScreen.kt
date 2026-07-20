package com.bubble.app.pairing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import com.bubble.app.ui.GlassPill
import com.bubble.app.ui.theme.BubbleColors

/**
 * Écran de pairage épuré (glassmorphism, fond nuit) : deux actions — générer ou scanner.
 * Toute la logique crypto/réseau vit dans [PairingController] ; ici, uniquement le rendu.
 */
@Composable
fun PairingScreen(controller: PairingController, modifier: Modifier = Modifier) {
    val state by controller.ui.collectAsState()

    Box(
        modifier = modifier.fillMaxSize().background(BubbleColors.Night),
        contentAlignment = Alignment.Center,
    ) {
        when (val ui = state) {
            is PairingUiState.Choice -> Choice(controller)
            is PairingUiState.ShowingInvite -> Invite(ui.qrPayload)
            is PairingUiState.Scanning -> Scan(controller)
            is PairingUiState.Paired -> Paired(ui.safetyNumber)
            is PairingUiState.Error -> Message(ui.message)
        }
    }
}

@Composable
private fun Choice(controller: PairingController) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Relier vos deux bulles", color = BubbleColors.Moon, fontSize = 22.sp)
        GlassPill(onClick = controller::generateInvite) {
            Text("Générer mon invitation", color = BubbleColors.Moon, fontSize = 15.sp)
        }
        GlassPill(onClick = controller::startScanning) {
            Text("Scanner l'invitation", color = BubbleColors.Moon, fontSize = 15.sp)
        }
    }
}

@Composable
private fun Invite(payload: String) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text("Fais scanner ce code", color = BubbleColors.Moon, fontSize = 18.sp)
        Box(
            Modifier
                .width(260.dp)
                .aspectRatio(1f)
                .clip(RoundedCornerShape(20.dp))
                .background(BubbleColors.NightHigh)
                .padding(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            QrCodeImage(payload = payload, modifier = Modifier.fillMaxSize())
        }
        Text("En attente de ton partenaire…", color = BubbleColors.Moon.copy(alpha = 0.6f), fontSize = 13.sp)
    }
}

@Composable
private fun Scan(controller: PairingController) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        QrScannerView(
            onScanned = controller::onQrScanned,
            modifier = Modifier.fillMaxSize(),
        )
        Text(
            "Vise l'invitation de ton partenaire",
            color = BubbleColors.Moon,
            fontSize = 14.sp,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 48.dp),
        )
    }
}

@Composable
private fun Paired(safetyNumber: String) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Vos bulles ne font qu'une", color = BubbleColors.AuraWarm, fontSize = 20.sp)
        Text(
            "Vérifiez ce nombre ensemble :",
            color = BubbleColors.Moon.copy(alpha = 0.7f),
            fontSize = 13.sp,
        )
        Text(
            safetyNumber,
            color = BubbleColors.Moon,
            fontSize = 18.sp,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun Message(message: String) {
    Text(message, color = BubbleColors.Moon, fontSize = 16.sp, textAlign = TextAlign.Center)
}
