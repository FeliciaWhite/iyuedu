package io.legado.app.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import io.legado.app.data.entities.BgmAIPrompt
import kotlinx.coroutines.flow.Flow

@Dao
interface BgmAIPromptDao {

    @Query("SELECT * FROM bgmAIPrompt")
    fun getAll(): Flow<List<BgmAIPrompt>>

    @Query("SELECT * FROM bgmAIPrompt")
    fun getAllList(): List<BgmAIPrompt>

    @Query("SELECT * FROM bgmAIPrompt WHERE id = :id")
    fun getById(id: Long): BgmAIPrompt?

    @Query("SELECT * FROM bgmAIPrompt WHERE isDefault = 1 LIMIT 1")
    fun getDefault(): BgmAIPrompt?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(vararg prompt: BgmAIPrompt)

    @Update
    fun update(vararg prompt: BgmAIPrompt)

    @Delete
    fun delete(vararg prompt: BgmAIPrompt)

    @Query("DELETE FROM bgmAIPrompt WHERE id = :id")
    fun deleteById(id: Long)
}
