package dev.thor.rombutler.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

/**
 * Thor color scheme — the app is dark mode ONLY by design decision,
 * so there is no light scheme and no dynamic color.
 */
private val ThorColorScheme = darkColorScheme(
    primary = ThorNeon,
    onPrimary = ThorOnNeon,
    primaryContainer = ThorNeonDim,
    onPrimaryContainer = ThorNeonBright,

    secondary = ThorGold,
    onSecondary = ThorOnGold,
    secondaryContainer = ThorGoldDim,
    onSecondaryContainer = ThorGoldBright,

    tertiary = ThorGoldBright,
    onTertiary = ThorOnGold,

    background = ThorBackground,
    onBackground = ThorTextPrimary,

    surface = ThorSurface,
    onSurface = ThorTextPrimary,
    surfaceVariant = ThorSurfaceHigh,
    onSurfaceVariant = ThorTextSecondary,
    surfaceContainer = ThorSurface,
    surfaceContainerHigh = ThorSurfaceHigh,
    surfaceContainerHighest = ThorSurfaceHighest,
    surfaceContainerLow = ThorSurface,
    surfaceContainerLowest = ThorBackground,

    error = ThorError,
    onError = ThorOnError,

    outline = ThorOutline,
    outlineVariant = ThorOutlineVariant,
)

/**
 * Rounded, card-heavy look: large radii for big touch targets.
 */
private val ThorShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/**
 * App theme wrapper. Always dark — ignores the system setting on purpose.
 */
@Composable
fun ThorRomButlerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = ThorColorScheme,
        typography = ThorTypography,
        shapes = ThorShapes,
        content = content,
    )
}
