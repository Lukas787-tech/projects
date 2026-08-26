package com.expensesplit.app.domain.split

import com.expensesplit.app.core.Money
import com.expensesplit.app.domain.model.SplitMethod

/**
 * Turns a bill total plus a chosen [SplitMethod] into per-member shares that always sum back to
 * the exact total. Every method funnels through [Money.splitEvenly] or [Money.allocateByWeights],
 * so rounding never leaks or invents a cent.
 */
object SplitCalculator {

    data class Participant(val memberId: Long, val weight: Double = 1.0)

    data class Result(
        val shares: Map<Long, Long>,
        val warnings: List<Warning> = emptyList(),
    ) {
        val totalMinor: Long get() = shares.values.sum()
    }

    enum class Warning {
        /** Percentages did not add up to 100 — they were rescaled proportionally. */
        PERCENTAGES_RESCALED,

        /** Custom amounts did not add up to the bill total — the difference was spread out. */
        CUSTOM_AMOUNTS_ADJUSTED,

        /** At least one weight was zero or negative and was treated as an equal share. */
        INVALID_WEIGHT_IGNORED,
    }

    fun calculate(
        totalMinor: Long,
        method: SplitMethod,
        participants: List<Participant>,
    ): Result {
        if (participants.isEmpty()) return Result(emptyMap())

        return when (method) {
            SplitMethod.EQUAL -> {
                val amounts = Money.splitEvenly(totalMinor, participants.size)
                Result(participants.mapIndexed { index, p -> p.memberId to amounts[index] }.toMap())
            }

            SplitMethod.PERCENTAGE -> {
                val warnings = mutableListOf<Warning>()
                val percentSum = participants.sumOf { it.weight }
                if (kotlin.math.abs(percentSum - 100.0) > 0.01 && percentSum > 0) {
                    warnings += Warning.PERCENTAGES_RESCALED
                }
                if (participants.any { it.weight <= 0.0 }) warnings += Warning.INVALID_WEIGHT_IGNORED
                val amounts = Money.allocateByWeights(totalMinor, participants.map { it.weight })
                Result(
                    participants.mapIndexed { index, p -> p.memberId to amounts[index] }.toMap(),
                    warnings,
                )
            }

            SplitMethod.SHARES -> {
                val warnings = if (participants.any { it.weight <= 0.0 }) {
                    listOf(Warning.INVALID_WEIGHT_IGNORED)
                } else {
                    emptyList()
                }
                val amounts = Money.allocateByWeights(totalMinor, participants.map { it.weight })
                Result(
                    participants.mapIndexed { index, p -> p.memberId to amounts[index] }.toMap(),
                    warnings,
                )
            }

            SplitMethod.CUSTOM -> {
                // Here `weight` carries a literal minor-unit amount typed by the user.
                val entered = participants.map { it.weight.toLong() }
                val enteredSum = entered.sum()
                if (enteredSum == totalMinor) {
                    return Result(participants.mapIndexed { i, p -> p.memberId to entered[i] }.toMap())
                }
                // Absorb the difference on the largest shares so nobody ends up with a negative one.
                val difference = totalMinor - enteredSum
                val adjusted = entered.toMutableList()
                val order = entered.indices.sortedByDescending { entered[it] }
                var remaining = difference
                var cursor = 0
                val step = if (remaining < 0) -1L else 1L
                while (remaining != 0L && order.isNotEmpty()) {
                    val index = order[cursor % order.size]
                    if (step < 0 && adjusted[index] <= 0) {
                        cursor++
                        if (cursor > order.size * 2) break
                        continue
                    }
                    adjusted[index] = adjusted[index] + step
                    remaining -= step
                    cursor++
                }
                Result(
                    participants.mapIndexed { i, p -> p.memberId to adjusted[i] }.toMap(),
                    listOf(Warning.CUSTOM_AMOUNTS_ADJUSTED),
                )
            }
        }
    }

    /** Validation helper for the split editor: how far the typed values are from the target. */
    fun customRemainderMinor(totalMinor: Long, enteredMinor: Collection<Long>): Long =
        totalMinor - enteredMinor.sum()

    fun percentageRemainder(percentages: Collection<Double>): Double = 100.0 - percentages.sum()
}
