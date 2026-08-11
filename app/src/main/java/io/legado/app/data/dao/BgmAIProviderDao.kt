package io.legado.app.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import io.legado.app.data.entities.BgmAIProvider
import kotlinx.coroutines.flow.Flow

@Dao
interface BgmAIProviderDao {

    @Query("SELECT * FROM bgmAIProvider")
    fun getAll(): Flow<List<BgmAIProvider>>

    @Query("SELECT * FROM bgmAIProvider")
    fun getAllList(): List<BgmAIProvider>

    @Query("SELECT * FROM bgmAIProvider WHERE id = :id")
    fun getById(id: Long): BgmAIProvider?

    @Query("SELECT * FROM bgmAIProvider WHERE enabled = 1 LIMIT 1")
    fun getEnabled(): BgmAIProvider?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(vararg provider: BgmAIProvider)

    @Update
    fun update(vararg provider: BgmAIProvider)

    @Delete
    fun delete(vararg provider: BgmAIProvider)

    @Query("DELETE FROM bgmAIProvider WHERE id = :id")
    fun deleteById(id: Long)
}
