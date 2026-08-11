package io.legado.app.data.entities

import android.os.Parcelable
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.parcelize.Parcelize

@Parcelize
@Entity(tableName = "tts_scripts")
data class TtsScript(
    @PrimaryKey(autoGenerate = true)
    var id: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "")
    var name: String = "",
    @ColumnInfo(defaultValue = "")
    var code: String = "",
    @ColumnInfo(defaultValue = "1")
    var isEnabled: Boolean = true,
    @ColumnInfo(name = "sortOrder", defaultValue = "0")
    var order: Int = Int.MIN_VALUE
) : Parcelable
