package dev.thor.rombutler.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.thor.rombutler.R
import dev.thor.rombutler.domain.model.Confidence

/**
 * Colored confidence label: neon blue = certain, gold = probable,
 * muted = unknown. The color language is consistent across all screens.
 */
@Composable
fun ConfidenceChip(confidence: Confidence, modifier: Modifier = Modifier) {
    val (container, content, label) = when (confidence) {
        Confidence.CERTAIN -> Triple(
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer,
            stringResource(R.string.confidence_certain),
        )
        Confidence.PROBABLE -> Triple(
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer,
            stringResource(R.string.confidence_probable),
        )
        Confidence.UNKNOWN -> Triple(
            MaterialTheme.colorScheme.surfaceContainerHighest,
            MaterialTheme.colorScheme.onSurfaceVariant,
            stringResource(R.string.confidence_unknown),
        )
    }
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = content,
        modifier = modifier
            .background(color = container, shape = MaterialTheme.shapes.extraSmall)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}
