package com.expensesplit.app.domain.model

/**
 * Categories ship as seeded rows so they can be translated at render time: [nameRes] points at a
 * string resource for built-in categories, while user-created ones carry a literal [customName].
 */
data class Category(
    val id: Long,
    val key: String,
    val nameRes: Int?,
    val customName: String?,
    val colorArgb: Long,
    val iconKey: String,
    /** Lower-cased merchant/keyword hints used by the auto-categorizer. */
    val keywords: List<String> = emptyList(),
    val isBuiltIn: Boolean = true,
)
