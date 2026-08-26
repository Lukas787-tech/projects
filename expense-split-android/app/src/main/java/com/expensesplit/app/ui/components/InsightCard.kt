package com.expensesplit.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.expensesplit.app.R
import com.expensesplit.app.domain.model.Insight
import com.expensesplit.app.domain.model.InsightSeverity
import com.expensesplit.app.ui.theme.LocalFinanceColors

/**
 * Renders one piece of spending advice.
 *
 * Text is resolved from resources here rather than in the engine, so insights are generated once
 * and displayed in whatever language is active at the time.
 */
@Composable
fun InsightCard(
    insight: Insight,
    baseCurrency: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val finance = LocalFinanceColors.current

    val accent = when (insight.severity) {
        InsightSeverity.CRITICAL -> finance.negative
        InsightSeverity.WARNING -> finance.warning
        InsightSeverity.SUGGESTION -> MaterialTheme.colorScheme.primary
        InsightSeverity.INFO -> finance.neutral
    }
    val icon = when (insight.severity) {
        InsightSeverity.CRITICAL -> Icons.Filled.ErrorOutline
        InsightSeverity.WARNING -> Icons.Filled.WarningAmber
        InsightSeverity.SUGGESTION -> Icons.Filled.Lightbulb
        InsightSeverity.INFO -> Icons.Filled.Info
    }

    val title = context.getString(insight.titleRes, *insight.titleArgs.toTypedArray())
    val body = context.getString(insight.bodyRes, *insight.bodyArgs.toTypedArray())

    Card(
        modifier = modifier.fillMaxWidth(),
        onClick = { onClick?.invoke() },
        enabled = onClick != null,
        colors = CardDefaults.cardColors(
            containerColor = accent.copy(alpha = 0.08f),
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(modifier = Modifier.padding(14.dp)) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (insight.potentialSavingMinor > 0) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResourceSaving(insight.potentialSavingMinor, baseCurrency),
                        style = MaterialTheme.typography.labelLarge,
                        color = finance.positive,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

@Composable
private fun stringResourceSaving(savingMinor: Long, currency: String): String =
    androidx.compose.ui.res.stringResource(
        R.string.insight_potential_saving,
        formatMoney(savingMinor, currency),
    )

/** Small coloured chip used for trend deltas ("+12.4% vs last month"). */
@Composable
fun DeltaChip(
    changePercent: Float,
    modifier: Modifier = Modifier,
    /** For spending, up is bad — pass false for metrics where up is good. */
    increaseIsNegative: Boolean = true,
) {
    val finance = LocalFinanceColors.current
    val isIncrease = changePercent > 0f
    val color: Color = when {
        kotlin.math.abs(changePercent) < 0.05f -> finance.neutral
        isIncrease == increaseIsNegative -> finance.negative
        else -> finance.positive
    }

    Text(
        text = formatSignedPercent(changePercent),
        style = MaterialTheme.typography.labelLarge,
        color = color,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier,
    )
}
