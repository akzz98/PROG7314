package za.co.munipulse.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Planning section C icon palette: deep teal and warm sand.
val PulseTeal = Color(0xFF0B6E4F)
val PulseSand = Color(0xFFE8DCC8)
private val Ink = Color(0xFF1C1B1F)

private val LightColors = lightColorScheme(
    primary = PulseTeal,
    onPrimary = Color.White,
    background = PulseSand,
    surface = PulseSand,
    onBackground = Ink,
    onSurface = Ink,
)

@Composable
fun MuniPulseTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LightColors,
        content = content,
    )
}
