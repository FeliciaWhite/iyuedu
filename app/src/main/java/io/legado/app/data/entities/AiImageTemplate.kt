package io.legado.app.data.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * AI 生图模板配置
 * 每个模板包含一套完整的生图参数（提示词、API 配置等）。
 * 默认模板（isDefault=true）对未绑定其他模板的书籍生效，全局唯一且不可删除。
 */
@Entity(tableName = "aiImageTemplate")
data class AiImageTemplate(
    @PrimaryKey
    val id: Long = System.currentTimeMillis(),
    var name: String = "",
    var modelUrl: String = "",
    var modelName: String = "",
    var modelKey: String = "",
    var imageSize: String = "784x1168",
    var imageStyle: String = "",
    var promptTemplate: String = "",
    var negativePrompt: String = "",
    /**
     * 正文过滤词语，多个用竖线 | 分隔。
     * 发送给 API 前，会将小说正文中包含的这些词语删除（替换为空）。
     * 例如：敏感词1|敏感词2|敏感词3
     */
    @ColumnInfo(defaultValue = "")
    var filterWords: String = "",
    @ColumnInfo(defaultValue = "0")
    var isDefault: Boolean = false,
    /**
     * 是否使用 base64 方式获取图片。
     * 开启后请求体会附带 `response_format=b64_json`，要求 API 直接返回 base64 图片数据，
     * 适用于返回临时 URL（秒级过期无法下载）的生图服务。
     */
    @ColumnInfo(defaultValue = "0")
    var useBase64Response: Boolean = false,
    /**
     * 是否对发给 API 的正文做角色标注。
     * 开启后，正文中的左双引号“右侧会插入 <<姓名（性别/年龄）>>，
     * 角色信息来自 AI 章节缓存文件（与导出小说“添加标注”同源）。
     * 关闭则按原正文发送。
     */
    @ColumnInfo(defaultValue = "0")
    var annotateRoles: Boolean = false,
    @ColumnInfo(defaultValue = "0")
    var lastUpdateTime: Long = System.currentTimeMillis(),
    /**
     * 排序序号。默认模板（isDefault=true）永远排最前，其余模板按此值升序排列。
     * 新建或修改模板时会被设为「最小的非默认 sortOrder - 1」，使其固定在默认模板之后的第 2 位。
     */
    @ColumnInfo(defaultValue = "0")
    var sortOrder: Int = 0
)
