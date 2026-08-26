package com.expensesplit.app.data.repository

import android.content.Context
import com.expensesplit.app.data.local.DefaultCategories
import com.expensesplit.app.data.local.dao.CategoryDao
import com.expensesplit.app.data.local.entity.CategoryEntity
import com.expensesplit.app.domain.model.Category
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CategoryRepository @Inject constructor(
    private val categoryDao: CategoryDao,
    private val context: Context,
) {

    /**
     * Built-in categories store the *name* of their string resource, so the integer id has to be
     * resolved at runtime. The lookup is cached because it happens for every row of every list.
     */
    private val resourceIdCache = mutableMapOf<String, Int>()

    val categories: Flow<List<Category>> = categoryDao.observeAll().map { entities ->
        entities.map { it.toDomain(resolveNameRes(it.nameResName)) }
    }

    suspend fun getAll(): List<Category> =
        categoryDao.getAll().map { it.toDomain(resolveNameRes(it.nameResName)) }

    suspend fun getAllById(): Map<Long, Category> = getAll().associateBy { it.id }

    suspend fun getById(id: Long): Category? =
        categoryDao.getById(id)?.let { it.toDomain(resolveNameRes(it.nameResName)) }

    suspend fun uncategorizedId(): Long =
        categoryDao.getByKey(DefaultCategories.UNCATEGORIZED_KEY)?.id ?: 1L

    suspend fun createCustom(name: String, colorArgb: Long, iconKey: String, keywords: List<String>): Long =
        categoryDao.insert(
            CategoryEntity(
                key = "custom_${name.lowercase().replace(' ', '_')}_${System.currentTimeMillis()}",
                nameResName = null,
                customName = name,
                colorArgb = colorArgb,
                iconKey = iconKey,
                keywords = keywords.map { it.lowercase().trim() }.filter { it.isNotBlank() },
                isBuiltIn = false,
                sortOrder = 50,
            ),
        )

    suspend fun updateCustom(category: Category) {
        val existing = categoryDao.getById(category.id) ?: return
        categoryDao.update(
            existing.copy(
                customName = category.customName,
                colorArgb = category.colorArgb,
                iconKey = category.iconKey,
                keywords = category.keywords,
            ),
        )
    }

    suspend fun deleteCustom(id: Long) = categoryDao.deleteCustom(id)

    /** Idempotent: seeding uses IGNORE on conflict, so re-running it never duplicates rows. */
    suspend fun seedIfEmpty() {
        if (categoryDao.count() == 0) {
            categoryDao.insertAll(DefaultCategories.seed)
        }
    }

    /** Display name for a category, resolving the string resource for built-in ones. */
    fun displayName(category: Category): String = when {
        category.customName != null -> category.customName
        category.nameRes != null && category.nameRes != 0 -> context.getString(category.nameRes)
        else -> category.key.replace('_', ' ').replaceFirstChar { it.uppercase() }
    }

    private fun resolveNameRes(resName: String?): Int? {
        if (resName.isNullOrBlank()) return null
        resourceIdCache[resName]?.let { return it }
        @Suppress("DiscouragedApi")
        val id = context.resources.getIdentifier(resName, "string", context.packageName)
        if (id != 0) resourceIdCache[resName] = id
        return id.takeIf { it != 0 }
    }
}
