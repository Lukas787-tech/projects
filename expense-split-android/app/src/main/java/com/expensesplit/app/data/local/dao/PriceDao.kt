package com.expensesplit.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.expensesplit.app.data.local.entity.FxRateEntity
import com.expensesplit.app.data.local.entity.PricePointEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PriceDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPricePoints(points: List<PricePointEntity>): List<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPricePoint(point: PricePointEntity): Long

    @Query("SELECT * FROM price_points WHERE normalizedItemName = :normalizedName ORDER BY observedOn ASC")
    suspend fun getHistory(normalizedName: String): List<PricePointEntity>

    @Query("SELECT * FROM price_points WHERE normalizedItemName = :normalizedName ORDER BY observedOn ASC")
    fun observeHistory(normalizedName: String): Flow<List<PricePointEntity>>

    @Query("SELECT * FROM price_points ORDER BY observedOn DESC")
    suspend fun getAllPricePoints(): List<PricePointEntity>

    @Query(
        """
        SELECT * FROM price_points
        WHERE normalizedItemName = :normalizedName AND unitPriceMinor < :belowMinor
        ORDER BY unitPriceMinor ASC
        LIMIT 1
        """
    )
    suspend fun getCheapestBelow(normalizedName: String, belowMinor: Long): PricePointEntity?

    @Query("DELETE FROM price_points WHERE id = :id")
    suspend fun deletePricePoint(id: Long)

    @Query("SELECT DISTINCT storeName FROM price_points ORDER BY storeName")
    suspend fun getKnownStores(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRates(rates: List<FxRateEntity>)

    @Query("SELECT * FROM fx_rates WHERE base = :base AND quote = :quote LIMIT 1")
    suspend fun getRate(base: String, quote: String): FxRateEntity?

    @Query("SELECT * FROM fx_rates WHERE base = :base")
    suspend fun getRatesFor(base: String): List<FxRateEntity>
}
