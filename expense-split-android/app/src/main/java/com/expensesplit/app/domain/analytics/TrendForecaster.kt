package com.expensesplit.app.domain.analytics

import com.expensesplit.app.domain.model.DailySpend
import com.expensesplit.app.domain.model.MonthlySpend
import com.expensesplit.app.domain.model.SpendingTrend
import com.expensesplit.app.domain.model.TrendDirection
import kotlin.math.abs

/**
 * Ordinary least-squares regression over the daily spend series.
 *
 * The projection is presented to the user as an estimate, never as a promise: [SpendingTrend.confidence]
 * carries the R² of the fit so the UI can soften the wording when the series is noisy — which,
 * for personal spending, it usually is.
 */
object TrendForecaster {

    /** Below this fraction of the mean, a slope is noise rather than a trend. */
    private const val STABLE_SLOPE_THRESHOLD = 0.02

    fun forecast(series: List<DailySpend>, projectionDays: Int): SpendingTrend {
        if (series.size < 3) {
            val total = series.sumOf { it.totalMinor }
            return SpendingTrend(TrendDirection.STABLE, 0.0, total, 0f)
        }

        val xs = series.indices.map { it.toDouble() }
        val ys = series.map { it.totalMinor.toDouble() }
        val (slope, intercept) = leastSquares(xs, ys)
        val rSquared = rSquared(xs, ys, slope, intercept)

        val meanDaily = ys.average()
        val direction = when {
            meanDaily <= 0.0 -> TrendDirection.STABLE
            abs(slope) / meanDaily < STABLE_SLOPE_THRESHOLD -> TrendDirection.STABLE
            slope > 0 -> TrendDirection.RISING
            else -> TrendDirection.FALLING
        }

        // Integrate the fitted line across the projection window rather than extrapolating a point.
        val startIndex = series.size.toDouble()
        var projected = 0.0
        for (offset in 0 until projectionDays) {
            projected += (intercept + slope * (startIndex + offset)).coerceAtLeast(0.0)
        }

        return SpendingTrend(
            direction = direction,
            slopePerDayMinor = slope,
            projectedNextPeriodMinor = projected.toLong(),
            confidence = rSquared.toFloat().coerceIn(0f, 1f),
        )
    }

    /** Month-over-month view used by the recap screen. */
    fun monthOverMonthChangePercent(series: List<MonthlySpend>): Float {
        if (series.size < 2) return 0f
        val sorted = series.sortedBy { it.month }
        val previous = sorted[sorted.size - 2].totalMinor
        val current = sorted.last().totalMinor
        if (previous <= 0) return 0f
        return (current - previous).toFloat() / previous * 100f
    }

    private fun leastSquares(xs: List<Double>, ys: List<Double>): Pair<Double, Double> {
        val n = xs.size
        val meanX = xs.average()
        val meanY = ys.average()
        var numerator = 0.0
        var denominator = 0.0
        for (i in 0 until n) {
            numerator += (xs[i] - meanX) * (ys[i] - meanY)
            denominator += (xs[i] - meanX) * (xs[i] - meanX)
        }
        val slope = if (denominator == 0.0) 0.0 else numerator / denominator
        return slope to (meanY - slope * meanX)
    }

    private fun rSquared(xs: List<Double>, ys: List<Double>, slope: Double, intercept: Double): Double {
        val meanY = ys.average()
        var residual = 0.0
        var totalVariance = 0.0
        for (i in xs.indices) {
            val predicted = intercept + slope * xs[i]
            residual += (ys[i] - predicted) * (ys[i] - predicted)
            totalVariance += (ys[i] - meanY) * (ys[i] - meanY)
        }
        if (totalVariance == 0.0) return 0.0
        return (1.0 - residual / totalVariance).coerceIn(0.0, 1.0)
    }
}
