# 角色标注功能说明文档

## 一、功能概述

在朗读界面的正文区域，**每一段对话的左双引号正上方行间距处**，绘制该对话对应的**角色名字**（由 AI 分析缓存生成）。点击角色名字区域，会弹出一个编辑弹窗，允许用户修改角色名字、性别、年龄，并支持通过 **9 个相邻角色快捷按钮** 快速填充信息。

---

## 二、代码文件清单

| 文件路径 | 作用 |
|---------|------|
| `app/src/main/java/io/legado/app/ui/book/read/page/DialogRoleManager.kt` | **核心管理类**：负责加载章节角色缓存、保存修改、收集相邻角色、绑定标注到文本行 |
| `app/src/main/java/io/legado/app/ui/book/read/page/entities/TextLine.kt` | **文本行实体**：持有 `roleAnnotations` 列表，负责在 `onDraw()` 中把角色名字绘制到左双引号正上方 |
| `app/src/main/java/io/legado/app/ui/book/read/page/ContentTextView.kt` | **阅读内容视图**：处理点击事件，检测是否点中角色标注区域，触发弹窗；弹窗保存后即时更新并重绘 |
| `app/src/main/java/io/legado/app/ui/book/read/ReadBookActivity.kt` | **阅读 Activity**：实现 `showRoleEditDialog()` 方法，构建弹窗 UI（含 3×3 相邻角色按钮网格、名字输入框、性别/年龄下拉框） |
| `app/src/main/java/io/legado/app/model/ReadBook.kt` | **阅读模型**：在加载/切换章节后调用 `DialogRoleManager.attachAnnotations()` 把标注绑定到当前章节 |
| `app/src/main/java/io/legado/app/help/config/AppConfig.kt` | **配置读写**：`showRoleAnnotation` 开关、`roleAnnotationOffset` 上下偏移量（dp） |
| `app/src/main/java/io/legado/app/constant/PreferKey.kt` | **SharedPreferences Key**：`showRoleAnnotation`、`roleAnnotationOffset` |
| `app/src/main/res/xml/pref_config_aloud.xml` | **设置界面 XML**：朗读配置页面中增加「显示对话角色标注」开关和「角色标注上下偏移」输入框 |
| `app/src/main/res/values/strings.xml` | **字符串资源**：上述两项设置的标题与摘要 |

---

## 三、角色名字怎样显示

### 3.1 数据来源
角色名字来源于 AI 分析生成的**章节 JSON 缓存文件**，存放在：
```
/storage/emulated/0/Download/chajian/xiaoshuo/<书名>/<章节标题>.json
```
JSON 结构示例：
```json
{
  "title": "第一章",
  "results": {
    "1": { "name": "张三", "gender": "男", "age": "男青年", "dialogText": "……" },
    "2": { "name": "李四", "gender": "女", "age": "少女", "dialogText": "……" }
  }
}
```
其中 `key`（如 `"1"`）是**对话序号（seq）**，按照正文中出现的 `"`（左双引号）顺序递增。

### 3.2 绑定时机
在 `ReadBook.kt` 中，当章节排版完成后，会执行：
```kotlin
DialogRoleManager.clearChapterCache(bookName, textChapter.title)
DialogRoleManager.attachAnnotations(textChapter, bookName)
```
`attachAnnotations()` 遍历当前章节所有 `TextPage` → `TextLine` → `Column`，每当遇到字符为 `“`（左双引号）时，就把 `seq` 加 1，然后去缓存里查对应的 `RoleInfo`。如果缓存中有名字，就创建一个 `RoleAnnotation` 对象，绑定到该 `TextLine`。

### 3.3 绘制位置
在 `TextLine.kt` 的两个绘制方法中都会绘制角色标注：
- `drawTextLine(view, canvas)` — 常规逐列绘制路径
- `fastDrawTextLine(view, canvas)` — 快速整行绘制路径

绘制代码核心逻辑：
```kotlin
if (AppConfig.showRoleAnnotation && !isTitle && !isImage && !isHtml) {
    val snapshot = synchronized(roleAnnotations) { ArrayList(roleAnnotations) }
    if (snapshot.isNotEmpty()) {
        val paint = DialogRoleManager.annotationPaint
        paint.textSize = ChapterProvider.contentPaint.textSize * 0.55f  // 字号为正文 55%
        paint.color = ReadBookConfig.textAccentColor                    // 使用正文强调色
        val fm = paint.fontMetrics
        val offsetPx = AppConfig.roleAnnotationOffset.dpToPx()          // 用户可调的上下偏移
        val textY = -fm.descent - offsetPx                              // 行顶部之上
        for (anno in snapshot) {
            canvas.drawText(anno.name, anno.labelStart, textY, paint)
        }
    }
}
```

#### 位置要点：
- **水平位置**：`anno.labelStart` = 该 `TextLine` 中左双引号列（`TextColumn`）的 `start` 坐标，即**名字左对齐在双引号正上方**。
- **垂直位置**：`textY = -fm.descent - offsetPx`。因为 `Canvas` 的坐标原点已经被平移到 `lineTop`，所以负数 Y 表示画在行顶部之上。默认 `offsetPx = 0`，名字紧贴行顶部上方；用户可以在设置里调整正负值来上下移动。
- **字号**：正文字体大小的 **55%**。
- **颜色**：`ReadBookConfig.textAccentColor`，与朗读高亮色一致，保证在各类主题下都醒目。
- **最大长度**：名字最多显示前 **5 个字**（`role.name.take(5)`），防止超长名字破坏排版。

---

## 四、点击检测与弹窗交互

### 4.1 点击检测
在 `ContentTextView.kt` 的 `click()` 方法中，**优先检测角色标注点击**（在普通文本点击之前）：
```kotlin
if (!debounceClick && checkRoleAnnotationClick(x, y)) {
    return true
}
```

`checkRoleAnnotationClick(x, y)` 逻辑：
1. 检查 `AppConfig.showRoleAnnotation` 是否开启。
2. 遍历当前可见的最多 3 个 `TextPage`（考虑滚动翻页）。
3. 对每个 `TextLine`，把 **Y 轴触摸范围**扩大到包含上方名字区域：
   - 顶部：`lineTop + relativeOffset - textHeight - 12.dpToPx()`
   - 底部：`lineBottom + relativeOffset`
4. 对 `TextLine` 中的每个 `RoleAnnotation`，进一步精确判断：
   - **X 范围**：`min(anno.labelStart, targetColumn.start) - 12.dpToPx()` 到 `max(anno.labelEnd, targetColumn.end) + 12.dpToPx()`（覆盖名字+双引号，左右各留 12dp 容错）。
   - **Y 范围**：名字顶部到行顶部以下 12dp（覆盖名字区域和一小段正文，方便点击）。
5. 命中后，调用 `callBack.showRoleEditDialog(...)` 打开弹窗。

### 4.2 弹窗显示内容
弹窗由 `ReadBookActivity.showRoleEditDialog()` 构建，使用 `AlertDialog`，包含以下元素（从上到下）：

1. **标题**：「编辑角色」
2. **相邻角色区域**（可选，如果有相邻角色）：
   - 标签文字「相邻角色」
   - **3×3 网格按钮**（最多 9 个）
3. **名字输入框**（`EditText`）：当前角色名字，可直接修改
4. **性别下拉框**（`Spinner`）：选项顺序为 `["男", "女", "特殊", ""]`，默认选中当前值。带灰色边框和 8dp 圆角。
5. **年龄下拉框**（`Spinner`）：选项顺序为 `["", "男童", "少年", "男青年", "男中年", "男老年", "女童", "少女", "女青年", "女中年", "女老年", "主角"]`，默认选中当前值。同样带灰色边框和 8dp 圆角。
6. **底部按钮**：「保存」「取消」。

点击保存后，会调用 `DialogRoleManager.saveRole()` 把修改写入对应章节的 JSON 缓存文件，并同时更新内存缓存和当前 `TextLine` 的标注对象，然后调用 `invalidate()` 即时重绘，用户可立即看到修改后的名字。

---

## 五、9 个相邻角色按钮怎样提取

### 5.1 提取逻辑所在文件
`DialogRoleManager.kt` — `collectNeighborRoles()` 方法。

### 5.2 提取规则（前 4 + 后 5 = 最多 9 个）
目标是围绕**当前对话**，在当前章节及相邻章节中找出**最近出现的不同角色**，凑成最多 9 个快捷按钮。排除当前正在编辑的角色名字（避免重复）。

具体步骤：

1. **当前章节向前找 4 个**（`seq` 递减，越近越优先）：
   - 从 `currentSeq - 1` 开始，往前遍历当前章节的缓存。
   - 取名字非空且未出现过的角色，最多 4 个。

2. **当前章节向后找 5 个**（`seq` 递增，越近越优先）：
   - 从 `currentSeq + 1` 开始，往后遍历当前章节的缓存。
   - 取名字非空且未出现过的角色，最多 5 个。

3. **如果当前章节前面不够 4 个，向前跨章节补充**：
   - 从**上一章**的末尾（最大 `seq`）往前遍历。
   - 仍然不够则继续向更前面的章节补充，直到凑够 4 个或没有更多章节。

4. **如果当前章节后面不够 5 个，向后跨章节补充**：
   - 从**下一章**的开头（`seq = 1`）往后遍历。
   - 仍然不够则继续向更后面的章节补充，直到凑够 5 个或没有更多章节。

5. **最终拼接**：
   - 把向前的结果**反转**（让离当前对话最近的排在最前面），再拼接向后的结果。
   - 最后 `take(9)`，确保最多只显示 9 个。

### 5.3 为什么这样设计
- **前 4 后 5**：因为用户通常要修改的角色，大概率是刚说过话的，或者即将说话的。靠前的角色更可能是上下文里的活跃角色。
- **跨章节**：避免章节边界处角色断档，保证用户在章节切换附近也能找到需要的角色。
- **去重**：使用 `seen` 集合保证同一个角色只出现一次，避免按钮重复。

### 5.4 按钮布局
在 `ReadBookActivity.kt` 中，9 个按钮使用 **3×3 网格布局**实现：
- 外层是一个垂直的 `LinearLayout`（`gridContainer`）。
- 每满 3 个按钮就创建一个新的水平 `LinearLayout`（`currentRow`），把 3 个按钮放进去。
- 每个按钮宽度权重 `1f`，即均分整行。
- 同行按钮之间右边距 `4dp`，第二行开始顶部边距 `4dp`。
- 按钮文字大小 11sp，内边距 4dp。

### 5.5 按钮点击效果
点击任意相邻角色按钮时，会自动把该角色的**名字、性别、年龄**分别填充到弹窗顶部的输入框和两个下拉框中，方便用户直接复用已有角色信息，减少手动输入。

---

## 六、配置项说明

在 App 的「朗读配置」页面中，新增两项设置：

| 设置项 | Key | 类型 | 默认值 | 说明 |
|--------|-----|------|--------|------|
| 显示对话角色标注 | `showRoleAnnotation` | SwitchPreference | `false` | 总开关，关闭后不再绘制名字、不再响应点击 |
| 角色标注上下偏移 | `roleAnnotationOffset` | EditTextPreference | `0` | 单位 dp。正数向上偏移，负数向下偏移。依赖 `showRoleAnnotation` |

---

## 七、时序/数据流总结

1. **AI 分析脚本**（外部）生成 `<章节>.json` 缓存 → 存入 `/storage/emulated/0/Download/chajian/xiaoshuo/<书名>/`
2. **打开/翻页章节** → `ReadBook.kt` 调用 `DialogRoleManager.attachAnnotations()`
3. **attachAnnotations** 遍历文本，遇到 `"` 就匹配 `seq`，把 `RoleAnnotation` 绑定到对应 `TextLine`
4. **onDraw** → `TextLine.kt` 在左双引号正上方绘制 `RoleAnnotation.name`
5. **用户点击名字区域** → `ContentTextView.checkRoleAnnotationClick()` 命中
6. **弹出编辑窗** → `ReadBookActivity.showRoleEditDialog()` 构建 AlertDialog，加载 9 个相邻角色按钮
7. **用户修改并保存** → `DialogRoleManager.saveRole()` 写 JSON 缓存，同时更新内存和当前 `TextLine`，即时重绘

---

## 八、注意事项

- 角色标注**仅在非标题、非图片、非 HTML 行**绘制，避免干扰特殊排版。
- 所有对 `roleAnnotations` 的读写都加了 `synchronized`，防止翻页动画与点击保存并发导致 `ConcurrentModificationException`。
- 名字显示最大 5 个字，超过会被截断，但弹窗里保存的是完整名字。
- 点击区域在 X 和 Y 方向都扩大了 12dp，提升触摸容错率。