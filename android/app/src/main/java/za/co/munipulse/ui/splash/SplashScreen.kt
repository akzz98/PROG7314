package za.co.munipulse.ui.splash

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import za.co.munipulse.ui.theme.MuniPulseTheme
import za.co.munipulse.ui.theme.PulseTeal

@Composable
fun SplashScreen(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        PulseMark()
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "MuniPulse SA",
            style = MaterialTheme.typography.headlineMedium,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Checking session…",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PulseMark() {
    Canvas(
        modifier = Modifier
            .size(96.dp)
            .semantics { contentDescription = "MuniPulse pulse mark" },
    ) {
        val center = Offset(size.width / 2f, size.height / 2f)
        drawCircle(
            color = PulseTeal,
            radius = size.minDimension * 0.42f,
            center = center,
            style = Stroke(width = size.minDimension * 0.06f),
        )
        drawCircle(
            color = PulseTeal,
            radius = size.minDimension * 0.08f,
            center = center,
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun SplashPreview() {
    MuniPulseTheme {
        SplashScreen()
    }
}
