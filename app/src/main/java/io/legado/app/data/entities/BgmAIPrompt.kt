package io.legado.app.data.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * BGM AI提示词配置
 */
@Entity(tableName = "bgmAIPrompt")
data class BgmAIPrompt(
    @PrimaryKey
    val id: Long = System.currentTimeMillis(),
    var name: String = "",
    var prompt: String = "",
    @ColumnInfo(defaultValue = "0")
    var isDefault: Boolean = false,
    @ColumnInfo(defaultValue = "0")
    var lastUpdateTime: Long = System.currentTimeMillis()
) {
    companion object {
        fun fromJson(json: Map<String, Any>): BgmAIPrompt {
            return BgmAIPrompt(
                id = (json["id"] as? Long) ?: System.currentTimeMillis(),
                name = (json["name"] as? String) ?: "",
                prompt = (json["prompt"] as? String) ?: "",
                isDefault = (json["isDefault"] as? Boolean) ?: false,
                lastUpdateTime = (json["lastUpdateTime"] as? Long) ?: System.currentTimeMillis()
            )
        }
    }
}
