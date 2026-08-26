package com.expensesplit.app.data.export

import android.content.Context
import android.net.Uri
import com.expensesplit.app.BuildConfig
import com.expensesplit.app.data.local.AppDatabase
import com.expensesplit.app.data.local.entity.BillEntity
import com.expensesplit.app.data.local.entity.BillShareEntity
import com.expensesplit.app.data.local.entity.BudgetEntity
import com.expensesplit.app.data.local.entity.CategoryEntity
import com.expensesplit.app.data.local.entity.ExpenseEntity
import com.expensesplit.app.data.local.entity.GroupEntity
import com.expensesplit.app.data.local.entity.MemberEntity
import com.expensesplit.app.data.local.entity.PricePointEntity
import com.expensesplit.app.data.local.entity.ReceiptEntity
import com.expensesplit.app.data.local.entity.ReceiptItemEntity
import com.expensesplit.app.data.local.entity.RecurringRuleEntity
import com.expensesplit.app.data.local.entity.SettlementEntity
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Full-database export and import as JSON, optionally encrypted with a user passphrase.
 *
 * Import is destructive by design — it restores a snapshot rather than merging two histories, which
 * would silently duplicate every expense. The caller is responsible for confirming with the user
 * first; [BackupSummary] lets the UI show exactly what a file contains before anything is replaced.
 */
@Singleton
class BackupManager @Inject constructor(
    private val context: Context,
    private val database: AppDatabase,
    private val cryptoManager: CryptoManager,
) {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    data class BackupSummary(
        val expenses: Int,
        val receipts: Int,
        val groups: Int,
        val bills: Int,
        val exportedAt: Long,
        val formatVersion: Int,
        val baseCurrency: String,
    )

    class IncompatibleBackupException(val foundVersion: Int) :
        Exception("Backup format v$foundVersion is newer than this app supports")

    suspend fun exportToFile(
        baseCurrency: String,
        passphrase: CharArray? = null,
        fileName: String? = null,
    ): File = withContext(Dispatchers.IO) {
        val payload = buildPayload(baseCurrency)
        val serialized = json.encodeToString(BackupPayload.serializer(), payload)
        val content = passphrase?.let { cryptoManager.encrypt(serialized, it) } ?: serialized

        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        val extension = if (passphrase != null) "esb" else "json"
        val directory = File(context.cacheDir, "exports").apply { mkdirs() }

        File(directory, fileName ?: "expensesplit-backup-$stamp.$extension").apply {
            writeText(content, Charsets.UTF_8)
        }
    }

    /** Reads a backup's header without touching the database, so the UI can confirm before import. */
    suspend fun inspect(uri: Uri, passphrase: CharArray? = null): BackupSummary =
        withContext(Dispatchers.IO) {
            val payload = readPayload(uri, passphrase)
            BackupSummary(
                expenses = payload.expenses.size,
                receipts = payload.receipts.size,
                groups = payload.groups.size,
                bills = payload.bills.size,
                exportedAt = payload.exportedAt,
                formatVersion = payload.formatVersion,
                baseCurrency = payload.baseCurrency,
            )
        }

    suspend fun importFromUri(uri: Uri, passphrase: CharArray? = null): BackupSummary =
        withContext(Dispatchers.IO) {
            val payload = readPayload(uri, passphrase)
            restore(payload)
            BackupSummary(
                expenses = payload.expenses.size,
                receipts = payload.receipts.size,
                groups = payload.groups.size,
                bills = payload.bills.size,
                exportedAt = payload.exportedAt,
                formatVersion = payload.formatVersion,
                baseCurrency = payload.baseCurrency,
            )
        }

    private fun readPayload(uri: Uri, passphrase: CharArray?): BackupPayload {
        val raw = context.contentResolver.openInputStream(uri)?.use { stream ->
            stream.readBytes().toString(Charsets.UTF_8)
        } ?: throw IllegalArgumentException("Could not open backup file")

        val decoded = when {
            cryptoManager.isEncrypted(raw) -> {
                val key = passphrase
                    ?: throw CryptoManager.DecryptionFailedException("This backup needs a passphrase")
                cryptoManager.decrypt(raw, key)
            }
            else -> raw
        }

        val payload = json.decodeFromString(BackupPayload.serializer(), decoded)
        if (payload.formatVersion > BackupPayload.CURRENT_FORMAT_VERSION) {
            throw IncompatibleBackupException(payload.formatVersion)
        }
        return payload
    }

    private suspend fun buildPayload(baseCurrency: String): BackupPayload {
        val expenseDao = database.expenseDao()
        val receiptDao = database.receiptDao()
        val groupDao = database.groupDao()

        return BackupPayload(
            appVersion = BuildConfig.VERSION_NAME,
            baseCurrency = baseCurrency,
            categories = database.categoryDao().getAll().map { entity ->
                BackupCategory(
                    id = entity.id,
                    key = entity.key,
                    nameResName = entity.nameResName,
                    customName = entity.customName,
                    colorArgb = entity.colorArgb,
                    iconKey = entity.iconKey,
                    keywords = entity.keywords,
                    isBuiltIn = entity.isBuiltIn,
                    sortOrder = entity.sortOrder,
                )
            },
            expenses = expenseDao.getAll().map { entity ->
                BackupExpense(
                    id = entity.id,
                    title = entity.title,
                    note = entity.note,
                    categoryId = entity.categoryId,
                    amountMinor = entity.amountMinor,
                    currency = entity.currency,
                    baseAmountMinor = entity.baseAmountMinor,
                    fxRate = entity.fxRate,
                    date = entity.date.toString(),
                    paymentMethod = entity.paymentMethod,
                    merchant = entity.merchant,
                    receiptId = entity.receiptId,
                    groupId = entity.groupId,
                    recurringRuleId = entity.recurringRuleId,
                    attachmentUri = entity.attachmentUri,
                    isSettled = entity.isSettled,
                    createdAt = entity.createdAt,
                    updatedAt = entity.updatedAt,
                )
            },
            receipts = receiptDao.getAllReceipts().map { entity ->
                BackupReceipt(
                    id = entity.id,
                    expenseId = entity.expenseId,
                    imageUri = entity.imageUri,
                    merchant = entity.merchant,
                    purchasedAt = entity.purchasedAt.toString(),
                    totalMinor = entity.totalMinor,
                    taxMinor = entity.taxMinor,
                    currency = entity.currency,
                    rawText = entity.rawText,
                    scanConfidence = entity.scanConfidence,
                    createdAt = entity.createdAt,
                )
            },
            receiptItems = receiptDao.getAllItems().map { entity ->
                BackupReceiptItem(
                    id = entity.id,
                    receiptId = entity.receiptId,
                    name = entity.name,
                    normalizedName = entity.normalizedName,
                    quantity = entity.quantity,
                    unitPriceMinor = entity.unitPriceMinor,
                    totalPriceMinor = entity.totalPriceMinor,
                    currency = entity.currency,
                    categoryId = entity.categoryId,
                )
            },
            groups = groupDao.getAllGroups().map { entity ->
                BackupGroup(entity.id, entity.name, entity.currency, entity.createdAt, entity.archived)
            },
            members = groupDao.getAllGroups().flatMap { group ->
                groupDao.getMembers(group.id).map { entity ->
                    BackupMember(
                        id = entity.id,
                        groupId = entity.groupId,
                        name = entity.name,
                        email = entity.email,
                        avatarColorArgb = entity.avatarColorArgb,
                        isSelf = entity.isSelf,
                    )
                }
            },
            bills = groupDao.getAllBills().map { entity ->
                BackupBill(
                    id = entity.id,
                    groupId = entity.groupId,
                    title = entity.title,
                    totalMinor = entity.totalMinor,
                    currency = entity.currency,
                    paidByMemberId = entity.paidByMemberId,
                    date = entity.date.toString(),
                    splitMethod = entity.splitMethod,
                    note = entity.note,
                    settled = entity.settled,
                    createdAt = entity.createdAt,
                )
            },
            billShares = groupDao.getAllGroups().flatMap { group ->
                groupDao.getSharesForGroup(group.id).map { entity ->
                    BackupBillShare(
                        entity.id,
                        entity.billId,
                        entity.memberId,
                        entity.shareMinor,
                        entity.weight,
                    )
                }
            },
            settlements = groupDao.getAllSettlements().map { entity ->
                BackupSettlement(
                    id = entity.id,
                    groupId = entity.groupId,
                    fromMemberId = entity.fromMemberId,
                    toMemberId = entity.toMemberId,
                    amountMinor = entity.amountMinor,
                    currency = entity.currency,
                    settledAt = entity.settledAt,
                    note = entity.note,
                )
            },
            budgets = database.budgetDao().getAll().map { entity ->
                BackupBudget(
                    id = entity.id,
                    categoryId = entity.categoryId,
                    period = entity.period,
                    limitMinor = entity.limitMinor,
                    currency = entity.currency,
                    startDate = entity.startDate.toString(),
                    alertThresholdPercent = entity.alertThresholdPercent,
                    active = entity.active,
                )
            },
            recurringRules = database.recurringDao().getAll().map { entity ->
                BackupRecurringRule(
                    id = entity.id,
                    title = entity.title,
                    categoryId = entity.categoryId,
                    amountMinor = entity.amountMinor,
                    currency = entity.currency,
                    paymentMethod = entity.paymentMethod,
                    merchant = entity.merchant,
                    frequency = entity.frequency,
                    interval = entity.interval,
                    nextRunDate = entity.nextRunDate.toString(),
                    endDate = entity.endDate?.toString(),
                    lastRunDate = entity.lastRunDate?.toString(),
                    active = entity.active,
                )
            },
            pricePoints = database.priceDao().getAllPricePoints().map { entity ->
                BackupPricePoint(
                    id = entity.id,
                    normalizedItemName = entity.normalizedItemName,
                    displayName = entity.displayName,
                    storeName = entity.storeName,
                    unitPriceMinor = entity.unitPriceMinor,
                    currency = entity.currency,
                    observedOn = entity.observedOn.toString(),
                    source = entity.source,
                    receiptItemId = entity.receiptItemId,
                )
            },
        )
    }

    /**
     * Replaces the database contents in a single transaction. Insert order follows the foreign-key
     * graph — categories and groups before the rows that point at them — and the whole thing rolls
     * back as one unit if any row is rejected.
     */
    private suspend fun restore(payload: BackupPayload) {
        database.withTransaction {
            database.maintenanceDao().clearUserData()

            database.categoryDao().insertAll(
                payload.categories.map { dto ->
                    CategoryEntity(
                        id = dto.id,
                        key = dto.key,
                        nameResName = dto.nameResName,
                        customName = dto.customName,
                        colorArgb = dto.colorArgb,
                        iconKey = dto.iconKey,
                        keywords = dto.keywords,
                        isBuiltIn = dto.isBuiltIn,
                        sortOrder = dto.sortOrder,
                    )
                },
            )

            payload.receipts.forEach { dto ->
                database.receiptDao().insertReceipt(
                    ReceiptEntity(
                        id = dto.id,
                        expenseId = dto.expenseId,
                        imageUri = dto.imageUri,
                        merchant = dto.merchant,
                        purchasedAt = LocalDate.parse(dto.purchasedAt),
                        totalMinor = dto.totalMinor,
                        taxMinor = dto.taxMinor,
                        currency = dto.currency,
                        rawText = dto.rawText,
                        scanConfidence = dto.scanConfidence,
                        createdAt = dto.createdAt,
                    ),
                )
            }

            database.receiptDao().insertItems(
                payload.receiptItems.map { dto ->
                    ReceiptItemEntity(
                        id = dto.id,
                        receiptId = dto.receiptId,
                        name = dto.name,
                        normalizedName = dto.normalizedName,
                        quantity = dto.quantity,
                        unitPriceMinor = dto.unitPriceMinor,
                        totalPriceMinor = dto.totalPriceMinor,
                        currency = dto.currency,
                        categoryId = dto.categoryId,
                    )
                },
            )

            payload.groups.forEach { dto ->
                database.groupDao().insertGroup(
                    GroupEntity(dto.id, dto.name, dto.currency, dto.createdAt, dto.archived),
                )
            }

            database.groupDao().insertMembers(
                payload.members.map { dto ->
                    MemberEntity(
                        id = dto.id,
                        groupId = dto.groupId,
                        name = dto.name,
                        email = dto.email,
                        avatarColorArgb = dto.avatarColorArgb,
                        isSelf = dto.isSelf,
                    )
                },
            )

            payload.bills.forEach { dto ->
                database.groupDao().insertBill(
                    BillEntity(
                        id = dto.id,
                        groupId = dto.groupId,
                        title = dto.title,
                        totalMinor = dto.totalMinor,
                        currency = dto.currency,
                        paidByMemberId = dto.paidByMemberId,
                        date = LocalDate.parse(dto.date),
                        splitMethod = dto.splitMethod,
                        note = dto.note,
                        settled = dto.settled,
                        createdAt = dto.createdAt,
                    ),
                )
            }

            database.groupDao().insertShares(
                payload.billShares.map { dto ->
                    BillShareEntity(dto.id, dto.billId, dto.memberId, dto.shareMinor, dto.weight)
                },
            )

            payload.settlements.forEach { dto ->
                database.groupDao().insertSettlement(
                    SettlementEntity(
                        id = dto.id,
                        groupId = dto.groupId,
                        fromMemberId = dto.fromMemberId,
                        toMemberId = dto.toMemberId,
                        amountMinor = dto.amountMinor,
                        currency = dto.currency,
                        settledAt = dto.settledAt,
                        note = dto.note,
                    ),
                )
            }

            payload.budgets.forEach { dto ->
                database.budgetDao().insert(
                    BudgetEntity(
                        id = dto.id,
                        categoryId = dto.categoryId,
                        period = dto.period,
                        limitMinor = dto.limitMinor,
                        currency = dto.currency,
                        startDate = LocalDate.parse(dto.startDate),
                        alertThresholdPercent = dto.alertThresholdPercent,
                        active = dto.active,
                    ),
                )
            }

            payload.recurringRules.forEach { dto ->
                database.recurringDao().insert(
                    RecurringRuleEntity(
                        id = dto.id,
                        title = dto.title,
                        categoryId = dto.categoryId,
                        amountMinor = dto.amountMinor,
                        currency = dto.currency,
                        paymentMethod = dto.paymentMethod,
                        merchant = dto.merchant,
                        frequency = dto.frequency,
                        interval = dto.interval,
                        nextRunDate = LocalDate.parse(dto.nextRunDate),
                        endDate = dto.endDate?.let(LocalDate::parse),
                        lastRunDate = dto.lastRunDate?.let(LocalDate::parse),
                        active = dto.active,
                    ),
                )
            }

            database.priceDao().insertPricePoints(
                payload.pricePoints.map { dto ->
                    PricePointEntity(
                        id = dto.id,
                        normalizedItemName = dto.normalizedItemName,
                        displayName = dto.displayName,
                        storeName = dto.storeName,
                        unitPriceMinor = dto.unitPriceMinor,
                        currency = dto.currency,
                        observedOn = LocalDate.parse(dto.observedOn),
                        source = dto.source,
                        receiptItemId = dto.receiptItemId,
                    )
                },
            )

            // Expenses go last: they reference categories, receipts and groups.
            database.expenseDao().insertAll(
                payload.expenses.map { dto ->
                    ExpenseEntity(
                        id = dto.id,
                        title = dto.title,
                        note = dto.note,
                        categoryId = dto.categoryId,
                        amountMinor = dto.amountMinor,
                        currency = dto.currency,
                        baseAmountMinor = dto.baseAmountMinor,
                        fxRate = dto.fxRate,
                        date = LocalDate.parse(dto.date),
                        paymentMethod = dto.paymentMethod,
                        merchant = dto.merchant,
                        receiptId = dto.receiptId,
                        groupId = dto.groupId,
                        recurringRuleId = dto.recurringRuleId,
                        attachmentUri = dto.attachmentUri,
                        isSettled = dto.isSettled,
                        createdAt = dto.createdAt,
                        updatedAt = dto.updatedAt,
                    )
                },
            )
        }
    }
}
