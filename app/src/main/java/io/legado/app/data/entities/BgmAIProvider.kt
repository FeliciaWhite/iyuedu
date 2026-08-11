package io.legado.app.data.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * BGM AI提供商配置
 */
@Entity(tableName = "bgmAIProvider")
data class BgmAIProvider(
    @PrimaryKey
    val id: Long = System.currentTimeMillis(),
    var name: String = "",
    var url: String = "",
    var apiKey: String = "",
    var modelId: String = "",
    @ColumnInfo(defaultValue = "0")
    var enabled: Boolean = false,
    @ColumnInfo(defaultValue = "0")
    var lastUpdateTime: Long = System.currentTimeMillis()
) {
    companion object {
        fun fromJson(json: Map<String, Any>): BgmAIProvider {
            return BgmAIProvider(
                id = (json["id"] as? Long) ?: System.currentTimeMillis(),
                name = (json["name"] as? String) ?: "",
                url = (json["url"] as? String) ?: "",
                apiKey = (json["apiKey"] as? String) ?: "",
                modelId = (json["modelId"] as? String) ?: "",
                enabled = (json["enabled"] as? Boolean) ?: false,
                lastUpdateTime = (json["lastUpdateTime"] as? Long) ?: System.currentTimeMillis()
            )
        }
    }
}
