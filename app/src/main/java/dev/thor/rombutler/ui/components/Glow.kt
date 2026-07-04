package dev.thor.rombutler.ui.components

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
/**
 * Subtle neon glow for cards and buttons. Implemented as a colored
 * shadow, so it stays cheap (no offscreen blur passes). Follows the
 * active theme's primary color (Thor blue, Odin violet, CRT green).
 *
 * @param elevation glow spread; keep small for "dezent".
 */
@Composable
fun Modifier.neonGlow(
    color: Color = MaterialTheme.colorScheme.primary,
    elevation: Dp = 8.dp,
    shape: Shape = MaterialTheme.shapes.medium,
): Modifier = shadow(
    elevation = elevation,
    shape = shape,
    ambientColor = color,
    spotColor = color,
)

/** Accent variant for primary call-to-action elements (theme secondary). */
@Composable
fun Modifier.goldGlow(
    elevation: Dp = 10.dp,
    shape: Shape = RoundedCornerShape(26.dp),
): Modifier = neonGlow(
    color = MaterialTheme.colorScheme.secondary,
    elevation = elevation,
    shape = shape,
)
