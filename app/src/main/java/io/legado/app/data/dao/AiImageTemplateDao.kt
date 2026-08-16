package io.legado.app.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import io.legado.app.data.entities.AiImageTemplate
import kotlinx.coroutines.flow.Flow

@Dao
interface AiImageTemplateDao {

    @Query("SELECT * FROM aiImageTemplate ORDER BY isDefault DESC, sortOrder ASC, lastUpdateTime ASC")
    fun getAll(): Flow<List<AiImageTemplate>>

    @Query("SELECT * FROM aiImageTemplate ORDER BY isDefault DESC, sortOrder ASC, lastUpdateTime ASC")
    fun getAllList(): List<AiImageTemplate>

    @Query("SELECT * FROM aiImageTemplate WHERE id = :id")
    fun getById(id: Long): AiImageTemplate?

    @Query("SELECT * FROM aiImageTemplate WHERE isDefault = 1 LIMIT 1")
    fun getDefault(): AiImageTemplate?

    @Query("SELECT MIN(sortOrder) FROM aiImageTemplate WHERE isDefault = 0")
    fun getMinSortOrderOfNonDefault(): Int?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(vararg template: AiImageTemplate)

    @Update
    fun update(vararg template: AiImageTemplate)

    @Delete
    fun delete(vararg template: AiImageTemplate)

    @Query("DELETE FROM aiImageTemplate WHERE id = :id")
    fun deleteById(id: Long)
}
