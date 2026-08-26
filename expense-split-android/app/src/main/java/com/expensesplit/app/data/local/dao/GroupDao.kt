package com.expensesplit.app.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.expensesplit.app.data.local.entity.BillEntity
import com.expensesplit.app.data.local.entity.BillShareEntity
import com.expensesplit.app.data.local.entity.GroupEntity
import com.expensesplit.app.data.local.entity.MemberEntity
import com.expensesplit.app.data.local.entity.SettlementEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface GroupDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertGroup(group: GroupEntity): Long

    @Update
    suspend fun updateGroup(group: GroupEntity)

    @Delete
    suspend fun deleteGroup(group: GroupEntity)

    @Query("DELETE FROM groups WHERE id = :id")
    suspend fun deleteGroupById(id: Long)

    @Query("SELECT * FROM groups WHERE archived = 0 ORDER BY createdAt DESC")
    fun observeGroups(): Flow<List<GroupEntity>>

    @Query("SELECT * FROM groups ORDER BY createdAt DESC")
    suspend fun getAllGroups(): List<GroupEntity>

    @Query("SELECT * FROM groups WHERE id = :id")
    suspend fun getGroup(id: Long): GroupEntity?

    @Query("SELECT * FROM groups WHERE id = :id")
    fun observeGroup(id: Long): Flow<GroupEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMember(member: MemberEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMembers(members: List<MemberEntity>): List<Long>

    @Update
    suspend fun updateMember(member: MemberEntity)

    @Query("DELETE FROM members WHERE id = :id")
    suspend fun deleteMemberById(id: Long)

    @Query("SELECT * FROM members WHERE groupId = :groupId ORDER BY isSelf DESC, name ASC")
    fun observeMembers(groupId: Long): Flow<List<MemberEntity>>

    @Query("SELECT * FROM members WHERE groupId = :groupId ORDER BY isSelf DESC, name ASC")
    suspend fun getMembers(groupId: Long): List<MemberEntity>

    @Query("SELECT * FROM members WHERE groupId = :groupId AND isSelf = 1 LIMIT 1")
    suspend fun getSelfMember(groupId: Long): MemberEntity?

    @Query("SELECT COUNT(*) FROM members WHERE groupId = :groupId")
    suspend fun countMembers(groupId: Long): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBill(bill: BillEntity): Long

    @Update
    suspend fun updateBill(bill: BillEntity)

    @Query("DELETE FROM bills WHERE id = :id")
    suspend fun deleteBillById(id: Long)

    @Query("SELECT * FROM bills WHERE groupId = :groupId ORDER BY date DESC, createdAt DESC")
    fun observeBills(groupId: Long): Flow<List<BillEntity>>

    @Query("SELECT * FROM bills WHERE groupId = :groupId ORDER BY date DESC, createdAt DESC")
    suspend fun getBills(groupId: Long): List<BillEntity>

    @Query("SELECT * FROM bills ORDER BY date DESC")
    suspend fun getAllBills(): List<BillEntity>

    @Query("SELECT * FROM bills WHERE id = :id")
    suspend fun getBill(id: Long): BillEntity?

    @Query("SELECT COUNT(*) FROM bills WHERE groupId = :groupId AND settled = 0")
    suspend fun countOpenBills(groupId: Long): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertShares(shares: List<BillShareEntity>): List<Long>

    @Query("DELETE FROM bill_shares WHERE billId = :billId")
    suspend fun deleteSharesForBill(billId: Long)

    @Query("SELECT * FROM bill_shares WHERE billId = :billId")
    suspend fun getShares(billId: Long): List<BillShareEntity>

    @Query(
        """
        SELECT s.* FROM bill_shares s
        INNER JOIN bills b ON b.id = s.billId
        WHERE b.groupId = :groupId
        """
    )
    suspend fun getSharesForGroup(groupId: Long): List<BillShareEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSettlement(settlement: SettlementEntity): Long

    @Query("DELETE FROM settlements WHERE id = :id")
    suspend fun deleteSettlementById(id: Long)

    @Query("SELECT * FROM settlements WHERE groupId = :groupId ORDER BY settledAt DESC")
    fun observeSettlements(groupId: Long): Flow<List<SettlementEntity>>

    @Query("SELECT * FROM settlements WHERE groupId = :groupId ORDER BY settledAt DESC")
    suspend fun getSettlements(groupId: Long): List<SettlementEntity>

    @Query("SELECT * FROM settlements ORDER BY settledAt DESC")
    suspend fun getAllSettlements(): List<SettlementEntity>
}
