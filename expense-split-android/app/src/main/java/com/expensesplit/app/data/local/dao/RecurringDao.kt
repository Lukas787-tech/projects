package com.expensesplit.app.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.expensesplit.app.data.local.entity.RecurringRuleEntity
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

@Dao
interface RecurringDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(rule: RecurringRuleEntity): Long

    @Update
    suspend fun update(rule: RecurringRuleEntity)

    @Delete
    suspend fun delete(rule: RecurringRuleEntity)

    @Query("DELETE FROM recurring_rules WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM recurring_rules ORDER BY nextRunDate ASC")
    fun observeAll(): Flow<List<RecurringRuleEntity>>

    @Query("SELECT * FROM recurring_rules ORDER BY nextRunDate ASC")
    suspend fun getAll(): List<RecurringRuleEntity>

    @Query("SELECT * FROM recurring_rules WHERE id = :id")
    suspend fun getById(id: Long): RecurringRuleEntity?

    @Query("SELECT * FROM recurring_rules WHERE active = 1 AND nextRunDate <= :onOrBefore")
    suspend fun getDue(onOrBefore: LocalDate): List<RecurringRuleEntity>

    @Query("SELECT * FROM recurring_rules WHERE active = 1 AND nextRunDate <= :onOrBefore")
    fun observeDue(onOrBefore: LocalDate): Flow<List<RecurringRuleEntity>>
}
