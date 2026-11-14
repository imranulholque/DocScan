package app.docscan

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

val Indigo = Color(0xFF4F46E5)
val Violet = Color(0xFF7C3AED)
val Teal = Color(0xFF0D9488)

private val Light = lightColorScheme(
    primary = Indigo, onPrimary = Color.White,
    primaryContainer = Color(0xFFE0E7FF), onPrimaryContainer = Color(0xFF1E1B4B),
    secondary = Teal, onSecondary = Color.White,
    secondaryContainer = Color(0xFFCCFBF1), onSecondaryContainer = Color(0xFF042F2E),
    background = Color(0xFFF5F6FB), onBackground = Color(0xFF111827),
    surface = Color.White, onSurface = Color(0xFF111827),
    surfaceVariant = Color(0xFFEDEFF7), onSurfaceVariant = Color(0xFF5B6275),
    outline = Color(0xFFC9CEDD), error = Color(0xFFDC2626),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF8B93FF), onPrimary = Color(0xFF14123D),
    primaryContainer = Color(0xFF2E2A8A), onPrimaryContainer = Color(0xFFE0E7FF),
    secondary = Color(0xFF5EEAD4), onSecondary = Color(0xFF042F2E),
    secondaryContainer = Color(0xFF134E4A), onSecondaryContainer = Color(0xFFCCFBF1),
    background = Color(0xFF0F1117), onBackground = Color(0xFFE8EAF2),
    surface = Color(0xFF181B24), onSurface = Color(0xFFE8EAF2),
    surfaceVariant = Color(0xFF232733), onSurfaceVariant = Color(0xFFA3A9BC),
    outline = Color(0xFF3A4054), error = Color(0xFFF87171),
)

@Composable
fun DocScanTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) Dark else Light,
        shapes = Shapes(
            small = RoundedCornerShape(12.dp),
            medium = RoundedCornerShape(16.dp),
            large = RoundedCornerShape(24.dp),
        ),
        content = content,
    )
}
