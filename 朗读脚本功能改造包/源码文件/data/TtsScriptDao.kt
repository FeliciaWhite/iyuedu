package io.legado.app.data.dao

import androidx.room.*
import io.legado.app.data.entities.TtsScript
import kotlinx.coroutines.flow.Flow

@Dao
interface TtsScriptDao {

    @get:Query("SELECT * FROM tts_scripts ORDER BY sortOrder ASC")
    val all: List<TtsScript>

    @Query("SELECT * FROM tts_scripts WHERE isEnabled = 1 ORDER BY sortOrder ASC")
    fun flowEnabled(): Flow<List<TtsScript>>

    @Query("SELECT * FROM tts_scripts ORDER BY sortOrder ASC")
    fun flowAll(): Flow<List<TtsScript>>

    @Query("SELECT * FROM tts_scripts WHERE id = :id")
    fun findById(id: Long): TtsScript?

    @get:Query("SELECT MIN(sortOrder) FROM tts_scripts")
    val minOrder: Int

    @get:Query("SELECT MAX(sortOrder) FROM tts_scripts")
    val maxOrder: Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(vararg ttsScript: TtsScript): List<Long>

    @Update
    fun update(vararg ttsScripts: TtsScript)

    @Delete
    fun delete(vararg ttsScripts: TtsScript)
}
