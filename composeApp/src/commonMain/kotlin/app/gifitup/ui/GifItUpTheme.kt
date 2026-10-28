@file:Suppress("MagicNumber") // The colors and sizes of the design are the content of this file.

package app.gifitup.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * The look of the app (#154, the owner's drafts in `docs/design/154`): deep purple surfaces, rounded cards, a purple
 * primary and an orange accent ([ColorScheme.tertiary]) for what needs the user's attention. The light and the dark
 * scheme the system asks for (`prefers-color-scheme` on the web) share the same roles.
 */
@Composable
internal fun GifItUpTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        shapes = GifItUpShapes,
        content = content,
    )
}

private val DarkColors =
    darkColorScheme(
        primary = Color(0xFF8B5CF6),
        onPrimary = Color(0xFF14092B),
        primaryContainer = Color(0xFF8B5CF6),
        onPrimaryContainer = Color(0xFF14092B),
        secondary = Color(0xFFB79CFF),
        onSecondary = Color(0xFF14092B),
        secondaryContainer = Color(0xFF2E2547),
        onSecondaryContainer = Color(0xFFF1ECFF),
        tertiary = Color(0xFFF5A55A),
        onTertiary = Color(0xFF2B1600),
        background = Color(0xFF100D1A),
        onBackground = Color(0xFFF4F1FA),
        surface = Color(0xFF100D1A),
        onSurface = Color(0xFFF4F1FA),
        surfaceVariant = Color(0xFF2A2238),
        onSurfaceVariant = Color(0xFFA9A1BC),
        surfaceContainer = Color(0xFF1C1727),
        surfaceContainerHigh = Color(0xFF2A2238),
        surfaceContainerHighest = Color(0xFF332A45),
        outline = Color(0xFF6F6785),
        error = Color(0xFFFF8A80),
        onError = Color(0xFF3B0A06),
    )

private val LightColors =
    lightColorScheme(
        primary = Color(0xFF6E3FD9),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFF8B5CF6),
        onPrimaryContainer = Color(0xFF14092B),
        secondary = Color(0xFF5B34B8),
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFE4DBF6),
        onSecondaryContainer = Color(0xFF1B1626),
        tertiary = Color(0xFF9A4A00),
        onTertiary = Color(0xFFFFFFFF),
        background = Color(0xFFFAF7FF),
        onBackground = Color(0xFF1B1626),
        surface = Color(0xFFFAF7FF),
        onSurface = Color(0xFF1B1626),
        surfaceVariant = Color(0xFFE4DBF6),
        onSurfaceVariant = Color(0xFF5E5670),
        surfaceContainer = Color(0xFFF0EBFA),
        surfaceContainerHigh = Color(0xFFE4DBF6),
        surfaceContainerHighest = Color(0xFFD9CEF0),
        outline = Color(0xFF857C99),
        error = Color(0xFFB3261E),
        onError = Color(0xFFFFFFFF),
    )

private val GifItUpShapes =
    Shapes(
        extraSmall = RoundedCornerShape(8.dp),
        small = RoundedCornerShape(12.dp),
        medium = RoundedCornerShape(16.dp),
        large = RoundedCornerShape(24.dp),
        extraLarge = RoundedCornerShape(28.dp),
    )
