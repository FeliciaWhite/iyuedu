package io.legado.app.ui.book.read.config

import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.os.Bundle
import android.preference.PreferenceManager
import android.text.TextWatcher
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.textfield.TextInputEditText
import io.legado.app.R
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class CharacterManagerDialog : DialogFragment() {

    private lateinit var rootView: View
    private lateinit var contentLayout: LinearLayout
    private lateinit var rvCharacters: RecyclerView
    private lateinit var tvCharacterLabel: TextView
    private lateinit var spinnerBook: Spinner
    private lateinit var btnSaveKey: Button
    private lateinit var btnRestoreKey: Button
    private lateinit var btnAddCharacter: Button
    private lateinit var btnMerge: Button
    private lateinit var btnChangeVoice: Button
    private lateinit var btnRelease: Button
    private lateinit var btnDeleteCharacter: Button
    private lateinit var btnCreateBook: Button
    private lateinit var btnBackupRestore: Button
    private lateinit var btnManageBooks: Button
    private lateinit var btnRefresh: Button
    private lateinit var btnSearch: ImageButton
    private lateinit var etSearchKeyword: EditText

    private var currentBook = "默认"
    private var bookList = mutableListOf<String>()
    private var characterRecords = JSONArray()
    private var markedIndices = mutableListOf<Int>()
    private var selectedIndex = -1
    private var longPressedIndex = -1
    private var spinnerInitialized = false  // 用于防止Spinner初始化时触发切换
    // 列表显示用的真实索引集合（筛选/搜索时只含命中项；无搜索时为全量索引）
    private var displayList: MutableList<Int> = mutableListOf()
    private var isSearchMode = false

    private lateinit var characterAdapter: CharacterAdapter

    // 颜色常量（适配深色模式）
    private var colorDefault = Color.parseColor("#333333")  // 默认文字色（浅色模式，深色模式会改为白色）
    private var colorMarked = Color.parseColor("#333333")   // 已标记未选中（浅色模式，深色模式会改为白色）
    private val colorSelected = Color.parseColor("#FF5722") // 已选中橙色
    private val colorOnlySelected = Color.parseColor("#1976D2") // 未标记已选中蓝色
    private val bgMarked = Color.parseColor("#FFF9C4")      // 已标记背景（浅色模式）
    private val bgMarkedDark = Color.parseColor("#4A4A4A")   // 已标记背景（深色模式）
    private val bgOnlySelected = Color.parseColor("#E3F2FD") // 未标记已选中背景（浅色模式）
    private val bgOnlySelectedDark = Color.parseColor("#1A3A5C") // 未标记已选中背景（深色模式）
    private val bgNormalDark = Color.parseColor("#2D2D2D")    // 普通背景（深色模式）

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        rootView = requireActivity().layoutInflater.inflate(
            R.layout.dialog_character_manager,
            null
        )
        initViews()
        initFileSystem()
        loadCharacters()
        setupListeners()
        setupRecyclerView()

        val dialog = Dialog(requireActivity(), R.style.dialog_style)
        dialog.setContentView(rootView)
        dialog.window?.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT
        )
        dialog.window?.setGravity(Gravity.CENTER)
        
        // 关键：在根布局上拦截触摸事件，确保RecyclerView区域的事件正确分发
        rootView.setOnTouchListener { v, event ->
            // 获取触摸点相对于 RecyclerView 的位置
            val rvBounds = android.graphics.Rect()
            rvCharacters.getGlobalVisibleRect(rvBounds)
            val touchX = event.rawX
            val touchY = event.rawY
            
            // 如果触摸点在 RecyclerView 区域内，返回 false 让 RecyclerView 处理滚动和点击
            if (touchX >= rvBounds.left && touchX <= rvBounds.right &&
                touchY >= rvBounds.top && touchY <= rvBounds.bottom) {
                // 在 RecyclerView 区域内，不拦截事件，让 RecyclerView 处理点击和滚动
                false
            } else {
                // 在其他区域，消费事件但不影响点击（按钮有自己的点击监听）
                true
            }
        }
        
        return dialog
    }

    override fun onStart() {
        super.onStart()
        // 页面渲染完毕后，切换到当前阅读书籍
        autoSwitchToCurrentBook()
    }

    private fun initViews() {
        contentLayout = rootView.findViewById(R.id.content_layout)
        rvCharacters = rootView.findViewById(R.id.rv_characters)
        tvCharacterLabel = rootView.findViewById(R.id.tv_character_label)
        spinnerBook = rootView.findViewById(R.id.spinner_book)
        btnSaveKey = rootView.findViewById(R.id.btn_save_key)
        btnRestoreKey = rootView.findViewById(R.id.btn_restore_key)
        btnAddCharacter = rootView.findViewById(R.id.btn_add_character)
        btnMerge = rootView.findViewById(R.id.btn_merge)
        btnChangeVoice = rootView.findViewById(R.id.btn_change_voice)
        btnRelease = rootView.findViewById(R.id.btn_release)
        btnDeleteCharacter = rootView.findViewById(R.id.btn_delete_character)
        btnCreateBook = rootView.findViewById(R.id.btn_create_book)
        btnBackupRestore = rootView.findViewById(R.id.btn_backup_restore)
        btnManageBooks = rootView.findViewById(R.id.btn_manage_books)
        btnRefresh = rootView.findViewById(R.id.btn_refresh)
        btnSearch = rootView.findViewById(R.id.btn_search)
        etSearchKeyword = rootView.findViewById(R.id.et_search_keyword)

        // 搜索模式切换：点搜索图标进入搜索（Spinner 变输入框），再次点击退出
        btnSearch.setOnClickListener { toggleSearchMode() }

        // 输入框实时筛选
        etSearchKeyword.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                updateCharacterList()
                updateLabel()
            }
        })
        // 输入完成（回车/完成）收起键盘
        etSearchKeyword.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                hideKeyboard()
                true
            } else false
        }

        // 初始化颜色（适配深色模式）
        initColors()
    }

    // 初始化颜色（适配深色模式）
    private fun initColors() {
        val isDarkMode = (resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
        if (isDarkMode) {
            colorDefault = Color.WHITE
            colorMarked = Color.WHITE
        } else {
            colorDefault = Color.parseColor("#333333")
            colorMarked = Color.parseColor("#333333")
        }
    }

    private fun initFileSystem() {
        val dir = File(baseDir)
        if (!dir.exists()) dir.mkdirs()

        // 确保cunfang.txt存在
        var cunfangContent = readTxtFile("cunfang.txt").ifEmpty { "默认" }
        currentBook = cunfangContent.trim()
        writeTxtFile("cunfang.txt", currentBook)

        // 确保liebiao.json存在且有效
        var liebiaoContent = readTxtFile("liebiao.json").ifEmpty { "[]" }
        bookList = try {
            val arr = JSONArray(liebiaoContent)
            if (arr.length() == 0) {
                writeTxtFile("liebiao.json", "[\"默认\"]")
                mutableListOf("默认")
            } else {
                val list = (0 until arr.length()).map { arr.getString(it).trim() }.toMutableList()
                // 确保包含"默认"
                if (!list.contains("默认")) {
                    list.add(0, "默认")
                    writeTxtFile("liebiao.json", JSONArray(list).toString())
                }
                list
            }
        } catch (e: Exception) {
            writeTxtFile("liebiao.json", "[\"默认\"]")
            mutableListOf("默认")
        }

        // 如果当前书籍不在列表中，切换到"默认"
        if (!bookList.contains(currentBook)) {
            currentBook = "默认"
            writeTxtFile("cunfang.txt", currentBook)
        }
    }

    // 自动切换到当前阅读书籍（完全复刻手动切换）
    private fun autoSwitchToCurrentBook() {
        try {
            val readBookName = io.legado.app.model.ReadBook.book?.name
            if (readBookName.isNullOrEmpty()) return
            
            // 检查书籍是否在列表中
            val matchedBook = bookList.find { it == readBookName }
            
            if (matchedBook != null) {
                // 书籍在列表中，直接切换
                if (matchedBook != currentBook) {
                    saveCurrentBookBeforeSwitch(matchedBook)
                }
            } else {
                // 书籍不在列表中（新读取的书籍），直接切换过去
                if (readBookName != currentBook) {
                    // 直接切换，不保存到列表
                    useBook(readBookName)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun loadCharacters() {
        // 优先从characterRecords.json加载（当前正在使用的书籍）
        val charRecordsContent = readTxtFile("characterRecords.json")
        val cunfangContent = readTxtFile("cunfang.txt").ifEmpty { "默认" }
        val cunfangBook = cunfangContent.trim()

        if (currentBook == cunfangBook && charRecordsContent.isNotEmpty()) {
            // 当前书籍等于cunfang.txt中的书籍，使用characterRecords.json
            characterRecords = try {
                JSONArray(charRecordsContent)
            } catch (e: Exception) {
                JSONArray()
            }
        } else {
            // 其他情况从shuming.{book}.json加载
            val fileName = "shuming.$currentBook.json"
            val content = readTxtFile(fileName)
            characterRecords = try {
                if (content.isNotEmpty()) JSONArray(content) else JSONArray()
            } catch (e: Exception) {
                JSONArray()
            }
        }
    }

    private fun setupRecyclerView() {
        characterAdapter = CharacterAdapter()
        rvCharacters.layoutManager = LinearLayoutManager(requireContext())
        rvCharacters.adapter = characterAdapter
        rvCharacters.setHasFixedSize(false)
        rvCharacters.isNestedScrollingEnabled = true
        
        characterAdapter.setOnItemClickListener { position ->
            val realIndex = getRealIndex(position)
            if (realIndex >= 0) handleItemClick(realIndex)
        }

        characterAdapter.setOnItemLongClickListener { position ->
            val realIndex = getRealIndex(position)
            if (realIndex >= 0) handleItemLongClick(realIndex)
            true
        }

        characterAdapter.setOnVoiceClickListener { position ->
            val realIndex = getRealIndex(position)
            if (realIndex >= 0) handleVoiceClick(realIndex)
        }

        updateCharacterList()
    }

    // 将列表中的位置映射回 characterRecords 中的真实索引
    private fun getRealIndex(position: Int): Int {
        if (position < 0 || position >= displayList.size) return -1
        return displayList[position]
    }

    private fun handleItemClick(position: Int) {
        if (markedIndices.contains(position)) {
            markedIndices.remove(position)
            if (selectedIndex == position) {
                selectedIndex = -1
            }
        } else {
            markedIndices.add(position)
            selectedIndex = position
        }
        updateCharacterList()
        updateLabel()
    }

    private fun handleVoiceClick(position: Int) {
        // 仅对当前点击的角色生效，设为选中角色（不再自动标记）
        selectedIndex = position
        updateCharacterList()
        updateLabel()
        // 直接弹出更换发音人对话框（仅对当前角色生效）
        showChangeVoiceDialog()
    }

    private fun previewCharacterVoice(position: Int) {
        val character = characterRecords.optJSONObject(position) ?: return
        val voice = character.optString("voice", "")
        if (voice.isBlank()) {
            toast("该角色未设置发音人，无法试听")
            return
        }
        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        val customText = prefs.getString("tts_preview_text", "你好，这是一段试听语音") ?: "你好，这是一段试听语音"
        val previewText = "\u201C <<$voice>>$customText\u201D"
        TtsPreviewHelper.previewVoice(
            fragment = this,
            previewText = previewText,
            voiceId = voice,
            onToast = { msg -> toast(msg) }
        )
    }

    // 字符串标准化（去特殊字符、trim）
    private fun normalizeString(str: String): String {
        return str.replace(Regex("[\u200B-\u200D\uFEFF]"), "").trim()
    }

    private fun handleItemLongClick(position: Int) {
        longPressedIndex = position
        showCharacterMenu(position)
    }

    private fun showCharacterMenu(position: Int) {
        val character = characterRecords.optJSONObject(position) ?: return
        val name = character.optString("name", "")

        val options = arrayOf(
            "修改角色",
            "删除角色",
            "设为主角",
            "固定发音人",
            "固定当前发音人",
            "固定性别年龄",
            "释放角色",
            "执行合并",
            "取消所有标记"
        )

        AlertDialog.Builder(requireContext())
            .setTitle("角色操作: $name")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showEditCharacterDialog(position)
                    1 -> deleteCharacter(position)
                    2 -> setMainCharacter(position)
                    3 -> showFixVoiceDialog(position)
                    4 -> fixCurrentVoice(position)
                    5 -> fixGenderAge(position)
                    6 -> releaseCharacter(position)
                    7 -> doMerge()
                    8 -> clearAllMarks()
                }
            }
            .show()
    }

    private fun updateCharacterList() {
        // 根据当前搜索关键词重建显示列表（displayList 存 characterRecords 的真实索引）
        val keyword = if (isSearchMode) {
            etSearchKeyword.text?.toString()?.trim().orEmpty()
        } else ""
        val keywords = keyword.split("|").map { it.trim() }.filter { it.isNotEmpty() }

        displayList.clear()
        for (i in 0 until characterRecords.length()) {
            val character = characterRecords.optJSONObject(i) ?: continue
            if (keywords.isEmpty()) {
                displayList.add(i)
            } else {
                // OR 匹配：主名或任一别名包含任一关键词（忽略大小写）即命中
                val name = character.optString("name", "").lowercase()
                val aliases = character.optString("aliases", "")
                    .split("|").map { it.trim().lowercase() }
                val hit = keywords.any { kw ->
                    name.contains(kw) || aliases.any { it.contains(kw) }
                }
                if (hit) displayList.add(i)
            }
        }
        characterAdapter.notifyDataSetChanged()
    }

    private fun updateLabel() {
        val matched = displayList.size
        val total = characterRecords.length()
        tvCharacterLabel.text = if (isSearchMode) {
            "角色列表 (匹配 $matched / 共 $total，已标记 ${markedIndices.size}):"
        } else {
            "角色列表 (已标记 ${markedIndices.size}):"
        }
    }

    // 进入/退出搜索模式：Spinner 与 搜索输入框 互斥显示
    private fun toggleSearchMode() {
        isSearchMode = !isSearchMode
        if (isSearchMode) {
            spinnerBook.visibility = View.GONE
            etSearchKeyword.visibility = View.VISIBLE
            etSearchKeyword.setText("")
            etSearchKeyword.requestFocus()
            showKeyboard(etSearchKeyword)
        } else {
            etSearchKeyword.visibility = View.GONE
            spinnerBook.visibility = View.VISIBLE
            hideKeyboard()
        }
        updateCharacterList()
        updateLabel()
    }

    private fun showKeyboard(view: View) {
        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideKeyboard() {
        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(etSearchKeyword.windowToken, 0)
    }

    private fun setupListeners() {
        // 密钥管理
        btnSaveKey.setOnClickListener { saveKey() }
        btnRestoreKey.setOnClickListener { restoreKey() }

        // 角色操作
        btnAddCharacter.setOnClickListener { showAddCharacterDialog() }
        btnMerge.setOnClickListener { doMerge() }
        btnChangeVoice.setOnClickListener { showChangeVoiceDialog() }
        btnRelease.setOnClickListener { doRelease() }
        btnDeleteCharacter.setOnClickListener { doDelete() }

        // 书籍操作
        btnCreateBook.setOnClickListener { createNewBook() }
        btnBackupRestore.setOnClickListener { backupRestore() }
        btnManageBooks.setOnClickListener { manageBooks() }
        btnRefresh.setOnClickListener { refresh() }

        // 书籍选择
        setupBookSpinner()
    }

    private fun setupBookSpinner() {
        val adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_item,
            bookList
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        spinnerBook.adapter = adapter
        
        val selectedIdx = bookList.indexOf(currentBook)
        if (selectedIdx >= 0) {
            spinnerBook.setSelection(selectedIdx)
        }

        spinnerBook.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                if (!spinnerInitialized) {
                    // 第一次初始化时不触发切换
                    spinnerInitialized = true
                    return
                }
                val selectedBook = bookList[pos]
                val currentBookName = readTxtFile("cunfang.txt").ifEmpty { "默认" }.trim()
                if (selectedBook != currentBookName) {
                    // 切换书籍
                    saveCurrentBookBeforeSwitch(selectedBook)
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    // 1. 切换书籍前保存当前书籍数据（复刻JS版本）
    private fun saveCurrentBookBeforeSwitch(newBookName: String) {
        try {
            // 从 characterRecords.json 读取当前角色数据，或使用内存中的数据
            var characterData = readTxtFile("characterRecords.json")
            if (characterData.isEmpty()) {
                characterData = characterRecords.toString()
            }
            val currentBookName = readTxtFile("cunfang.txt").ifEmpty { "默认" }.trim()
            if (currentBookName.isNotEmpty()) {
                val shumingFileName = "shuming.$currentBookName.json"
                writeTxtFile(shumingFileName, characterData)
                createGengxinFile()
            }
            useBook(newBookName)
        } catch (e: Exception) {
            e.printStackTrace()
            useBook(newBookName)
        }
    }

    // 2. 加载目标书籍数据（复刻JS版本）
    private fun useBook(bookName: String) {
        try {
            writeTxtFile("cunfang.txt", bookName)
            val shumingFileName = "shuming.$bookName.json"
            var bookData = readTxtFile(shumingFileName)
            if (bookData.isEmpty()) {
                bookData = "[]"
            }
            val parsedData = try {
                JSONArray(bookData)
            } catch (e: Exception) {
                JSONArray()
            }
            characterRecords = parsedData
            
            // 切换书籍后刷新UI
            currentBook = bookName
            markedIndices.clear()
            selectedIndex = -1
            saveCharacters()
            updateCharacterList()
            updateLabel()
            updateSpinner()
            toast("已切换到书籍: $bookName")
        } catch (e: Exception) {
            e.printStackTrace()
            characterRecords = JSONArray()
            writeTxtFile("characterRecords.json", "[]")
            updateCharacterList()
            toast("切换失败，已重置角色数据")
        }
    }

    // 创建 gengxin.json（复刻JS版本的createGengxinFile逻辑）
    private fun createGengxinFile() {
        try {
            val saveRecords = JSONArray()
            for (i in 0 until characterRecords.length()) {
                val char = characterRecords.optJSONObject(i) ?: continue
                val record = JSONObject().apply {
                    put("name", char.optString("name", ""))
                    put("aliases", char.optString("aliases", ""))
                    put("voice", char.optString("voice", ""))  // 暂不做映射
                    put("gender", char.optString("gender", ""))
                    put("age", char.optString("age", ""))
                    put("usageCount", char.optInt("usageCount", 0))
                    // 保留其他字段
                    if (char.has("fixedVoice")) put("fixedVoice", char.optBoolean("fixedVoice", false))
                    if (char.has("fixedGenderAge")) put("fixedGenderAge", char.optBoolean("fixedGenderAge", false))
                    if (char.has("genderAgeHistory")) put("genderAgeHistory", char.opt("genderAgeHistory"))
                }
                saveRecords.put(record)
            }
            writeTxtFile("gengxin.json", saveRecords.toString())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // ==================== 密钥管理 ====================

    private fun getCurrentKey(): String {
        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        return prefs.getString("miyue", "") ?: ""
    }

    private fun saveKeyToPrefs(key: String) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        prefs.edit().putString("miyue", key).apply()
    }

    private fun saveKey() {
        // 第一步：弹出输入框让用户输入密钥内容
        val keyInput = TextInputEditText(requireContext()).apply {
            hint = "输入密钥内容"
            setPadding(50, 30, 50, 30)
        }

        AlertDialog.Builder(requireContext())
            .setTitle("保存密钥 - 第1步")
            .setView(keyInput)
            .setPositiveButton("下一步") { _, _ ->
                val keyContent = keyInput.text?.toString()?.trim() ?: ""
                if (keyContent.isNotEmpty()) {
                    // 第二步：弹出输入框让用户输入密钥名称
                    val nameInput = TextInputEditText(requireContext()).apply {
                        hint = "输入密钥名称"
                        setPadding(50, 30, 50, 30)
                    }

                    AlertDialog.Builder(requireContext())
                        .setTitle("保存密钥 - 第2步")
                        .setView(nameInput)
                        .setPositiveButton("保存") { _, _ ->
                            val name = nameInput.text?.toString()?.trim() ?: ""
                            if (name.isNotEmpty()) {
                                // 保存到 keys.json 并更新 miyue.txt
                                saveKeyToJson(name, keyContent)
                                toast("密钥已保存: $name")
                            } else {
                                toast("名称不能为空")
                            }
                        }
                        .setNegativeButton("取消", null)
                        .show()
                } else {
                    toast("密钥内容不能为空")
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // 保存密钥到 keys.json，并同时保存纯密钥内容到 miyue.txt
    private fun saveKeyToJson(name: String, key: String) {
        val keyMap = getKeyMap()
        keyMap.put(name, key)
        saveKeyMap(keyMap)
        // 同时更新 miyue.txt（保存纯密钥内容）
        writeTxtFile("miyue.txt", key)
    }

    private fun restoreKey() {
        val keyMap = getKeyMap()
        val names = getKeyNames(keyMap)

        if (names.isEmpty()) {
            toast("没有已保存的密钥")
            return
        }

        // 创建自定义对话框
        val dialog = Dialog(requireContext())
        dialog.setContentView(R.layout.dialog_key_list)
        dialog.window?.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT
        )

        val listView = dialog.findViewById<ListView>(R.id.lv_keys)
        val btnDelete = dialog.findViewById<Button>(R.id.btn_delete_key)
        val btnShowCurrent = dialog.findViewById<Button>(R.id.btn_show_current)
        val btnCancel = dialog.findViewById<Button>(R.id.btn_cancel)

        // 自定义适配器：每项右侧带“修改”图标
        val adapter = object : ArrayAdapter<String>(
            requireContext(),
            R.layout.dialog_key_item,
            R.id.tv_key_name,
            names
        ) {
            override fun getView(position: Int, convertView: android.view.View?, parent: android.view.ViewGroup): android.view.View {
                val view = convertView
                    ?: layoutInflater.inflate(R.layout.dialog_key_item, parent, false)
                val name = names[position]
                val tv = view.findViewById<TextView>(R.id.tv_key_name)
                tv.text = name
                // 恢复原先的正常文字颜色，避免变浅看不清
                tv.setTextColor(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.primaryText))
                view.findViewById<ImageButton>(R.id.btn_edit_key).setOnClickListener {
                    dialog.dismiss()
                    modifyKey(name)
                }
                return view
            }
        }
        listView.adapter = adapter

        // 点击密钥恢复（点击名称区域）
        listView.setOnItemClickListener { _, _, position, _ ->
            val name = names[position]
            val key = keyMap.optString(name, "")
            writeTxtFile("miyue.txt", key)
            saveKeyToPrefs(key)
            toast("已恢复密钥: $name")
            dialog.dismiss()
        }

        // 底部按钮
        btnDelete.setOnClickListener {
            dialog.dismiss()
            selectKeyToDelete()
        }
        btnShowCurrent.setOnClickListener {
            dialog.dismiss()
            showCurrentKeyDialog()
        }
        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun selectKeyToRestore() {
        val keyMap = getKeyMap()
        val names = getKeyNames(keyMap)

        if (names.isEmpty()) {
            toast("没有已保存的密钥")
            return
        }

        AlertDialog.Builder(requireContext())
            .setTitle("恢复密钥")
            .setItems(names.toTypedArray()) { _, which ->
                val name = names[which]
                val key = keyMap.optString(name, "")
                // 只把纯密钥内容保存到 miyue.txt
                writeTxtFile("miyue.txt", key)
                saveKeyToPrefs(key)
                toast("已恢复密钥: $name")
            }
            .setNegativeButton("取消", null)
            .show()
    }
    
    private fun showCurrentKeyDialog() {
        // 显示 miyue.txt 文件内容（纯密钥内容）
        val miyueContent = readTxtFile("miyue.txt")
        if (miyueContent.isEmpty()) {
            toast("当前密钥为空")
            return
        }
        
        AlertDialog.Builder(requireContext())
            .setTitle("当前密钥内容")
            .setMessage(miyueContent)
            .setPositiveButton("复制") { _, _ ->
                val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("密钥", miyueContent))
                toast("已复制到剪贴板")
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun selectKeyToDelete() {
        val keyMap = getKeyMap()
        val names = getKeyNames(keyMap)

        if (names.isEmpty()) {
            toast("没有已保存的密钥")
            return
        }

        val dialog = Dialog(requireContext())
        dialog.setContentView(R.layout.dialog_key_delete_multi)
        dialog.window?.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT
        )

        val listView = dialog.findViewById<ListView>(R.id.lv_keys_multi)
        val btnConfirm = dialog.findViewById<Button>(R.id.btn_confirm_delete)
        val btnCancel = dialog.findViewById<Button>(R.id.btn_cancel_delete)

        // 使用 CheckedTextView 支持多选显示
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_list_item_checked, names)
        listView.adapter = adapter

        btnConfirm.setOnClickListener {
            val selectedIndices = mutableListOf<Int>()
            for (i in 0 until listView.count) {
                if (listView.isItemChecked(i)) {
                    selectedIndices.add(i)
                }
            }
            if (selectedIndices.isEmpty()) {
                toast("请选择要删除的密钥")
                return@setOnClickListener
            }
            dialog.dismiss()
            // 倒序删除避免索引错乱
            selectedIndices.sortedDescending().forEach { index ->
                deleteKey(names[index])
            }
            toast("已删除 ${selectedIndices.size} 个密钥")
        }

        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun selectKeyToModify() {
        val keyMap = getKeyMap()
        val names = getKeyNames(keyMap)

        if (names.isEmpty()) {
            toast("没有已保存的密钥")
            return
        }

        AlertDialog.Builder(requireContext())
            .setTitle("修改密钥")
            .setItems(names.toTypedArray()) { _, which ->
                val name = names[which]
                modifyKey(name)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun getKeyNames(keyMap: JSONObject): MutableList<String> {
        val names = mutableListOf<String>()
        val keysIterator = keyMap.keys()
        while (keysIterator.hasNext()) {
            names.add(keysIterator.next())
        }
        return names
    }

    private fun deleteKey(name: String) {
        AlertDialog.Builder(requireContext())
            .setTitle("确认删除")
            .setMessage("确定要删除密钥 '$name' 吗？")
            .setPositiveButton("删除") { _, _ ->
                val keyMap = getKeyMap()
                keyMap.remove(name)
                saveKeyMap(keyMap)
                // 注意：删除密钥不修改 miyue.txt，只修改 keys.json
                toast("已删除密钥: $name")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun modifyKey(name: String) {
        val keyMap = getKeyMap()
        val currentKey = keyMap.optString(name, "")

        // 创建包含两个输入框的布局
        val container = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 20, 50, 20)
        }

        // 名称输入框
        val nameLabel = TextView(requireContext()).apply {
            text = "密钥名称"
            textSize = 14f
            setPadding(0, 0, 0, 10)
        }
        val nameInput = TextInputEditText(requireContext()).apply {
            hint = "输入密钥名称"
            setText(name)
            setPadding(0, 0, 0, 20)
        }

        // 密钥内容输入框
        val keyLabel = TextView(requireContext()).apply {
            text = "密钥内容"
            textSize = 14f
            setPadding(0, 0, 0, 10)
        }
        val keyInput = TextInputEditText(requireContext()).apply {
            hint = "输入密钥内容"
            setText(currentKey)
        }

        container.addView(nameLabel)
        container.addView(nameInput)
        container.addView(keyLabel)
        container.addView(keyInput)

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle("修改密钥")
            .setView(container)
            .setNegativeButton("取消", null)
            .create()

        // 保存：仅更新 keys.json
        dialog.setButton(AlertDialog.BUTTON_POSITIVE, "保存") { _, _ ->
            applyModifyKey(keyMap, name, nameInput, keyInput, false)
        }
        // 保存并使用：更新 keys.json 并设为当前生效密钥（最右边）
        dialog.setButton(AlertDialog.BUTTON_NEUTRAL, "保存并使用") { _, _ ->
            applyModifyKey(keyMap, name, nameInput, keyInput, true)
        }
        dialog.show()
    }

    /**
     * 执行密钥修改。
     * @param use true 时除写入 keys.json 外，还将该密钥设为当前生效（写入 miyue.txt 与偏好）
     */
    private fun applyModifyKey(
        keyMap: JSONObject,
        oldName: String,
        nameInput: TextInputEditText,
        keyInput: TextInputEditText,
        use: Boolean
    ) {
        val newName = nameInput.text?.toString()?.trim() ?: ""
        val newKey = keyInput.text?.toString()?.trim() ?: ""
        if (newName.isNotEmpty() && newKey.isNotEmpty()) {
            if (oldName != newName) {
                keyMap.remove(oldName)
            }
            keyMap.put(newName, newKey)
            saveKeyMap(keyMap)
            if (use) {
                writeTxtFile("miyue.txt", newKey)
                saveKeyToPrefs(newKey)
                toast("已修改并启用密钥: $newName")
            } else {
                toast("已修改密钥: $newName")
            }
        } else {
            toast("名称和密钥内容都不能为空")
        }
    }

    private fun saveKeyMap(keyMap: JSONObject) {
        // 只保存到 keys.json，不再写入 miyue.txt
        val keysArray = JSONArray()
        val keysIterator = keyMap.keys()
        while (keysIterator.hasNext()) {
            keysArray.put(keysIterator.next())
        }
        val result = JSONObject()
        result.put("keys", keysArray)
        val keysIterator2 = keyMap.keys()
        while (keysIterator2.hasNext()) {
            val keyName = keysIterator2.next()
            result.put(keyName, keyMap.getString(keyName))
        }
        writeTxtFile("keys.json", result.toString())
    }

    private fun getKeyMap(): JSONObject {
        // 从 keys.json 读取所有密钥
        val content = readTxtFile("keys.json")
        return try {
            if (content.isNotEmpty()) JSONObject(content) else JSONObject()
        } catch (e: Exception) {
            JSONObject()
        }
    }

    // ==================== 角色管理 ====================

    private fun showAddCharacterDialog() {
        val input = TextInputEditText(requireContext()).apply {
            hint = "输入角色名称"
            setPadding(50, 30, 50, 30)
        }

        AlertDialog.Builder(requireContext())
            .setTitle("新增角色")
            .setView(input)
            .setPositiveButton("确定") { _, _ ->
                val name = input.text?.toString()?.trim() ?: ""
                if (name.isNotEmpty()) {
                    addCharacter(name)
                } else {
                    toast("名称不能为空")
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun addCharacter(name: String) {
        val character = JSONObject().apply {
            put("name", name)
            put("aliases", name) // 别名字符串格式
            put("gender", "")
            put("age", "")
            put("voice", "")
            put("usageCount", 100) // 新角色默认100
        }
        characterRecords.put(character)
        saveCharacters()
        updateCharacterList()
        toast("已添加角色: $name")
    }

    private fun showEditCharacterDialog(position: Int) {
        val character = characterRecords.optJSONObject(position) ?: return
        val currentName = character.optString("name", "")
        val currentAliases = character.optString("aliases", "").ifEmpty { currentName }

        // 创建包含两个输入框的布局
        val container = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 20, 50, 20)
        }

        // 主名输入框
        val nameLabel = TextView(requireContext()).apply {
            text = "主名"
            textSize = 14f
            setPadding(0, 0, 0, 10)
        }
        val nameInput = TextInputEditText(requireContext()).apply {
            hint = "输入角色主名"
            setText(currentName)
            setPadding(0, 0, 0, 20)
        }

        // 别名输入框
        val aliasesLabel = TextView(requireContext()).apply {
            text = "别名（多个用|分隔）"
            textSize = 14f
            setPadding(0, 0, 0, 10)
        }
        val aliasesInput = TextInputEditText(requireContext()).apply {
            hint = "输入角色别名，多个别名用|分隔"
            setText(currentAliases)
        }

        container.addView(nameLabel)
        container.addView(nameInput)
        container.addView(aliasesLabel)
        container.addView(aliasesInput)

        AlertDialog.Builder(requireContext())
            .setTitle("修改角色")
            .setView(container)
            .setPositiveButton("确定") { _, _ ->
                val newName = nameInput.text?.toString()?.trim() ?: ""
                val newAliases = aliasesInput.text?.toString()?.trim() ?: ""
                if (newName.isNotEmpty()) {
                    character.put("name", newName)
                    character.put("aliases", newAliases)
                    saveCharacters()
                    updateCharacterList()
                    toast("已修改角色: $newName")
                } else {
                    toast("主名不能为空")
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun deleteCharacter(position: Int) {
        val character = characterRecords.optJSONObject(position) ?: return
        val name = character.optString("name", "")

        AlertDialog.Builder(requireContext())
            .setTitle("确认删除")
            .setMessage("确定要删除角色 '$name' 吗？")
            .setPositiveButton("删除") { _, _ ->
                characterRecords.remove(position)
                // 调整标记索引
                val newMarkedIndices = mutableListOf<Int>()
                markedIndices.forEach { idx ->
                    when {
                        idx < position -> newMarkedIndices.add(idx)
                        idx > position -> newMarkedIndices.add(idx - 1)
                    }
                }
                markedIndices.clear()
                markedIndices.addAll(newMarkedIndices)
                if (selectedIndex == position) {
                    selectedIndex = -1
                } else if (selectedIndex > position) {
                    selectedIndex--
                }
                saveCharacters()
                updateCharacterList()
                updateLabel()
                toast("已删除角色: $name")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun setMainCharacter(position: Int) {
        for (i in 0 until characterRecords.length()) {
            val char = characterRecords.optJSONObject(i)
            if (i == position) {
                char?.put("age", "主角")
                char?.put("usageCount", 100)
            } else {
                // 如果之前是主角，清除主角标记（保留age字段但改usageCount）
                if (char?.optString("age", "") == "主角") {
                    char.put("usageCount", 0)
                }
            }
        }
        saveCharacters()
        updateCharacterList()
        toast("已设为主角")
    }

    private fun showFixVoiceDialog(position: Int) {
        longPressedIndex = position
        showVoiceCategoryDialog { category ->
            showVoiceListDialog(category) { voice ->
                fixVoice(position, voice)
            }
        }
    }

    // 发音人分类选择弹窗
    private fun showVoiceCategoryDialog(onCategorySelected: (String) -> Unit) {
        val categories = arrayOf(
            "女童", "少女", "女青年", "女中年", "女老年",
            "男童", "少年", "男青年", "男中年", "男老年",
            "男主", "女主", "特殊"
        )

        AlertDialog.Builder(requireContext())
            .setTitle("选择发音人分类")
            .setItems(categories) { _, which ->
                val category = categories[which]
                onCategorySelected(category)
            }
            .setNegativeButton("全部") { _, _ ->
                showVoiceListDialog("全部", onCategorySelected)
            }
            .setNeutralButton("搜索") { _, _ ->
                showVoiceSearchDialog(onCategorySelected)
            }
            .setPositiveButton("取消", null)
            .show()
    }

    // 发音人搜索弹窗
    private fun showVoiceSearchDialog(onVoiceSelected: (String) -> Unit) {
        val editText = androidx.appcompat.widget.AppCompatEditText(requireContext()).apply {
            hint = "输入关键词搜索发音人"
        }
        AlertDialog.Builder(requireContext())
            .setTitle("搜索发音人")
            .setView(editText)
            .setPositiveButton("搜索") { _, _ ->
                val keyword = editText.text?.toString()?.trim() ?: ""
                if (keyword.isNotEmpty()) {
                    showVoiceListDialog(keyword, onVoiceSelected)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // 发音人列表选择弹窗（根据分类筛选）
    private fun showVoiceListDialog(category: String, onVoiceSelected: (String) -> Unit) {
        val allVoices = getVoiceList()
        // 根据关键词匹配发音人
        val filteredVoices = when {
            category == "全部" -> allVoices
            category.isNotEmpty() -> allVoices.filter { v -> v.contains(category) }
            else -> allVoices
        }

        // 如果筛选结果为空，显示全部列表
        val displayList = if (filteredVoices.isEmpty()) allVoices else filteredVoices

        if (displayList.isEmpty()) {
            toast("发音人列表为空")
            return
        }

        val title = if (category == "全部") "选择发音人 (全部)" else "选择发音人 ($category)"

        AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setItems(displayList.toTypedArray()) { _, which ->
                val voice = displayList[which]
                onVoiceSelected(voice)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun fixVoice(position: Int, voice: String) {
        val character = characterRecords.optJSONObject(position) ?: return
        character.put("voice", voice)
        character.put("fixedVoice", true)
        saveCharacters()
        updateCharacterList()
        toast("已固定发音人: $voice")
    }

    private fun fixCurrentVoice(position: Int) {
        val character = characterRecords.optJSONObject(position) ?: return
        val currentVoice = character.optString("voice", "auto")
        character.put("fixedVoice", true)
        saveCharacters()
        updateCharacterList()
        toast("已固定当前发音人: $currentVoice")
    }

    private fun fixGenderAge(position: Int) {
        val character = characterRecords.optJSONObject(position) ?: return
        character.put("fixedGenderAge", true)
        saveCharacters()
        updateCharacterList()
        toast("已固定性别年龄")
    }

    private fun releaseCharacter(position: Int) {
        val character = characterRecords.optJSONObject(position) ?: return
        val charName = character.optString("name", "")
        val normalizedName = normalizeString(charName)
        val aliasesStr = character.optString("aliases", "")

        if (aliasesStr.isEmpty() || aliasesStr.trim() == "") {
            toast("该角色没有别名，无需释放")
            return
        }

        val aliases = aliasesStr.split("|")
        val newCharacters = mutableListOf<JSONObject>()

        aliases.forEach { alias ->
            val normalizedAlias = normalizeString(alias.trim())
            if (normalizedAlias == normalizedName) {
                return@forEach
            }

            // 检查是否已存在同名角色
            var exists = false
            for (i in 0 until characterRecords.length()) {
                val recordName = normalizeString(characterRecords.optJSONObject(i)?.optString("name", "") ?: "")
                if (recordName == normalizedAlias) {
                    exists = true
                    break
                }
            }

            if (!exists) {
                val newRecord = JSONObject().apply {
                    put("name", alias.trim())
                    put("aliases", alias.trim())
                    put("voice", "")
                    put("gender", "")
                    put("age", "")
                    put("usageCount", 0)
                }
                newCharacters.add(newRecord)
            }
        }

        if (newCharacters.isEmpty()) {
            toast("所有别名角色已存在，无需释放")
            return
        }

        // 原角色仅保留自身名称作为别名
        character.put("aliases", charName)

        // 使用新数组正确插入
        val newRecords = JSONArray()
        for (i in 0 until characterRecords.length()) {
            if (i == position) {
                // 添加原角色
                newRecords.put(character)
                // 添加释放出来的新角色
                newCharacters.forEach { newRecords.put(it) }
            } else {
                characterRecords.optJSONObject(i)?.let { newRecords.put(it) }
            }
        }
        characterRecords = newRecords

        markedIndices.clear()
        selectedIndex = -1
        saveCharacters()
        updateCharacterList()
        updateLabel()
        toast("角色释放成功")
    }

    private fun clearAllMarks() {
        markedIndices.clear()
        selectedIndex = -1
        updateCharacterList()
        updateLabel()
        toast("已取消所有标记")
    }

    // ==================== 批量操作 ====================

    private fun doMerge() {
        if (markedIndices.isEmpty()) {
            toast("请先标记角色（单击）")
            return
        }

        // 使用最后标记的角色作为主角色
        val mainIndex = markedIndices.last()
        if (markedIndices.size == 1) {
            toast("至少需要标记两个角色才能合并")
            return
        }
        performMerge(mainIndex)
    }

    private fun performMerge(mainIndex: Int) {
        val mainChar = characterRecords.optJSONObject(mainIndex) ?: return
        val mainName = mainChar.optString("name", "")

        // 收集待合并的角色（排除主角色）
        val mergeIndices = markedIndices.filter { it != mainIndex }
        if (mergeIndices.isEmpty()) {
            toast("请标记至少一个要合并的角色")
            return
        }

        // 收集所有别名
        val allAliasesSet = mutableSetOf<String>()

        // 收集主角色现有的别名（字符串格式，用|分割）
        val mainAliasesStr = mainChar.optString("aliases", "")
        if (mainAliasesStr.isNotEmpty()) {
            mainAliasesStr.split("|").forEach { allAliasesSet.add(it.trim()) }
        } else {
            allAliasesSet.add(mainName)
        }

        // 收集待合并角色的别名
        mergeIndices.sortedDescending().forEach { pos ->
            val char = characterRecords.optJSONObject(pos) ?: return@forEach
            val aliasesStr = char.optString("aliases", "")
            if (aliasesStr.isNotEmpty()) {
                aliasesStr.split("|").forEach { allAliasesSet.add(it.trim()) }
            } else {
                allAliasesSet.add(char.optString("name", ""))
            }
        }

        // 移除待合并的角色（倒序删除避免索引混乱）
        mergeIndices.sortedDescending().forEach { pos ->
            characterRecords.remove(pos)
        }

        // 更新主角色
        mainChar.put("name", mainName)
        mainChar.put("aliases", allAliasesSet.joinToString("|"))

        markedIndices.clear()
        selectedIndex = -1
        saveCharacters()
        updateCharacterList()
        updateLabel()
        toast("已合并角色: $mainName")
    }

    private fun showChangeVoiceDialog() {
        if (selectedIndex < 0) {
            toast("请先选中一个角色")
            return
        }

        showVoiceCategoryDialog { category ->
            showVoiceListDialog(category) { voice ->
                changeVoiceForSelected(voice)
            }
        }
    }

    private fun changeVoiceForSelected(voice: String) {
        val pos = selectedIndex
        if (pos < 0) return
        characterRecords.optJSONObject(pos)?.put("voice", voice)
        saveCharacters()
        updateCharacterList()
        toast("已更换发音人: $voice")
    }

    private fun doRelease() {
        if (markedIndices.isEmpty()) {
            toast("请先标记角色")
            return
        }

        val releaseList = markedIndices.sortedDescending()
        val newRecords = JSONArray()
        var originalIndex = 0

        while (originalIndex < characterRecords.length()) {
            // 检查当前索引是否是待释放的角色
            if (releaseList.contains(originalIndex)) {
                val character = characterRecords.optJSONObject(originalIndex)
                if (character != null) {
                    val charName = character.optString("name", "")
                    val normalizedName = normalizeString(charName)
                    val aliasesStr = character.optString("aliases", "")

                    // 先添加原角色
                    newRecords.put(character)

                    // 如果有别名，释放为新角色
                    if (aliasesStr.isNotEmpty() && aliasesStr.trim() != "") {
                        val aliases = aliasesStr.split("|")
                        aliases.forEach { alias ->
                            val normalizedAlias = normalizeString(alias.trim())
                            if (normalizedAlias != normalizedName) {
                                // 检查是否已存在同名角色
                                var exists = false
                                for (i in 0 until characterRecords.length()) {
                                    val recordName = normalizeString(characterRecords.optJSONObject(i)?.optString("name", "") ?: "")
                                    if (recordName == normalizedAlias) {
                                        exists = true
                                        break
                                    }
                                }

                                if (!exists) {
                                    val newRecord = JSONObject().apply {
                                        put("name", alias.trim())
                                        put("aliases", alias.trim())
                                        put("voice", "")
                                        put("gender", "")
                                        put("age", "")
                                        put("usageCount", 0)
                                    }
                                    newRecords.put(newRecord)
                                }
                            }
                        }

                        // 原角色仅保留自身名称作为别名
                        character.put("aliases", charName)
                    }
                }
            } else {
                // 非释放角色，直接复制
                characterRecords.optJSONObject(originalIndex)?.let {
                    newRecords.put(it)
                }
            }
            originalIndex++
        }

        characterRecords = newRecords
        markedIndices.clear()
        selectedIndex = -1
        saveCharacters()
        updateCharacterList()
        updateLabel()
        toast("角色释放成功")
    }

    private fun doDelete() {
        if (markedIndices.isEmpty()) {
            toast("请先标记角色")
            return
        }

        val count = markedIndices.size
        AlertDialog.Builder(requireContext())
            .setTitle("确认删除")
            .setMessage("确定要删除 $count 个角色吗？")
            .setPositiveButton("删除") { _, _ ->
                markedIndices.sortedDescending().forEach { pos ->
                    characterRecords.remove(pos)
                }
                markedIndices.clear()
                selectedIndex = -1
                saveCharacters()
                updateCharacterList()
                updateLabel()
                toast("已删除 $count 个角色")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ==================== 书籍管理 ====================

    private fun createNewBook() {
        val input = TextInputEditText(requireContext()).apply {
            hint = "输入新书名称"
            setPadding(50, 30, 50, 30)
        }

        AlertDialog.Builder(requireContext())
            .setTitle("创建新书")
            .setView(input)
            .setPositiveButton("创建") { _, _ ->
                val name = input.text?.toString()?.trim() ?: ""
                if (name.isNotEmpty()) {
                    if (!bookList.contains(name)) {
                        bookList.add(name)
                        saveBookList()
                        currentBook = name
                        writeTxtFile("cunfang.txt", currentBook)
                        characterRecords = JSONArray()
                        saveCharacters()
                        updateSpinner()
                        markedIndices.clear()
                        selectedIndex = -1
                        updateCharacterList()
                        updateLabel()
                        toast("已创建新书: $name")
                    } else {
                        toast("书籍已存在")
                    }
                } else {
                    toast("名称不能为空")
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun manageBooks() {
        val options = arrayOf("删除书籍")
        
        AlertDialog.Builder(requireContext())
            .setTitle("管理书籍")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> deleteBook()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun deleteBook() {
        if (bookList.size <= 1) {
            toast("至少保留一本书")
            return
        }

        val bookNames = bookList.toTypedArray()
        AlertDialog.Builder(requireContext())
            .setTitle("选择要删除的书籍")
            .setItems(bookNames) { _, which ->
                val bookToDelete = bookList[which]
                AlertDialog.Builder(requireContext())
                    .setTitle("确认删除")
                    .setMessage("确定要删除书籍 '$bookToDelete' 及其所有角色数据吗？")
                    .setPositiveButton("删除") { _, _ ->
                        if (bookToDelete == currentBook) {
                            currentBook = bookList.filter { it != bookToDelete }.first()
                            writeTxtFile("cunfang.txt", currentBook)
                            loadCharacters()
                        }
                        bookList.remove(bookToDelete)
                        saveBookList()
                        deleteFile("shuming.$bookToDelete.json")
                        updateSpinner()
                        markedIndices.clear()
                        selectedIndex = -1
                        updateCharacterList()
                        updateLabel()
                        toast("已删除书籍: $bookToDelete")
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun updateSpinner() {
        val adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_item,
            bookList
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        spinnerBook.adapter = adapter
        val selectedIdx = bookList.indexOf(currentBook)
        if (selectedIdx >= 0) {
            spinnerBook.setSelection(selectedIdx)
        }
    }

    // ==================== 备份恢复 ====================

    private fun backupRestore() {
        val options = arrayOf("备份到剪贴板", "从文本恢复", "密钥恢复")

        AlertDialog.Builder(requireContext())
            .setTitle("备份恢复")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> backupToClipboard()
                    1 -> restoreFromInput()
                    2 -> restoreKeysFromJson()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // 从JSON恢复密钥
    private fun restoreKeysFromJson() {
        val input = TextInputEditText(requireContext()).apply {
            hint = "粘贴包含密钥的JSON数据"
            minLines = 5
            setPadding(50, 30, 50, 30)
        }

        AlertDialog.Builder(requireContext())
            .setTitle("密钥恢复")
            .setView(input)
            .setPositiveButton("恢复") { _, _ ->
                val json = input.text?.toString()?.trim() ?: ""
                if (json.isNotEmpty()) {
                    try {
                        val importedCount = parseAndSaveKeys(json)
                        toast("已恢复 $importedCount 个密钥")
                    } catch (e: Exception) {
                        toast("JSON格式错误: ${e.message}")
                    }
                } else {
                    toast("请输入JSON数据")
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // 解析JSON并保存密钥
    private fun parseAndSaveKeys(json: String): Int {
        // 解析外层JSON数组
        val outerArray = JSONArray(json)
        val keyMap = getKeyMap() // 保留现有密钥
        var importCount = 0

        for (i in 0 until outerArray.length()) {
            val item = outerArray.optJSONObject(i) ?: continue
            val config = item.optJSONObject("config") ?: continue
            val source = config.optJSONObject("source") ?: continue
            val data = source.optJSONObject("data") ?: continue
            val keyListJson = data.optString("keyListJson", "")

            if (keyListJson.isEmpty()) continue

            // 解析内层JSON字符串
            val innerObj = JSONObject(keyListJson)

            // 使用for循环遍历keys
            for (keyName in innerObj.keys()) {
                val keyObj = innerObj.optJSONObject(keyName) ?: continue
                val keyValue = keyObj.optString("value", "")

                if (keyValue.isNotEmpty()) {
                    // URL解码
                    val decodedValue = try {
                        java.net.URLDecoder.decode(keyValue, "UTF-8")
                    } catch (e: Exception) {
                        keyValue
                    }
                    keyMap.put(keyName, decodedValue)
                    importCount++
                }
            }
        }

        saveKeyMap(keyMap)
        return importCount
    }

    private fun backupToClipboard() {
        val json = characterRecords.toString()
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("角色备份", json))
        toast("已复制到剪贴板")
    }

    private fun restoreFromInput() {
        val input = TextInputEditText(requireContext()).apply {
            hint = "粘贴JSON数据"
            minLines = 5
            setPadding(50, 30, 50, 30)
        }

        AlertDialog.Builder(requireContext())
            .setTitle("从文本恢复")
            .setView(input)
            .setPositiveButton("恢复") { _, _ ->
                val json = input.text?.toString()?.trim() ?: ""
                if (json.isNotEmpty()) {
                    try {
                        characterRecords = JSONArray(json)
                        saveCharacters()
                        markedIndices.clear()
                        selectedIndex = -1
                        updateCharacterList()
                        updateLabel()
                        toast("已恢复角色数据")
                    } catch (e: Exception) {
                        toast("JSON格式错误")
                    }
                } else {
                    toast("请输入JSON数据")
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ==================== 辅助方法 ====================

    private fun refresh() {
        loadCharacters()
        markedIndices.clear()
        selectedIndex = -1
        updateCharacterList()
        updateLabel()
        toast("已刷新")
    }

    private fun saveCharacters() {
        // 同时保存到三个文件
        val jsonData = characterRecords.toString()
        writeTxtFile("characterRecords.json", jsonData)
        val fileName = "shuming.$currentBook.json"
        writeTxtFile(fileName, jsonData)
        createGengxinFile()
    }

    private fun saveBookList() {
        val arr = JSONArray()
        bookList.forEach { arr.put(it) }
        writeTxtFile("liebiao.json", arr.toString())
    }

    private fun getVoiceList(): List<String> {
        return try {
            val content = readTxtFile("fayinren.json")
            if (content.isNotEmpty()) {
                val arr = JSONArray(content)
                (0 until arr.length()).map { arr.getString(it) }
            } else {
                listOf("auto")
            }
        } catch (e: Exception) {
            listOf("auto")
        }
    }

    // ==================== 文件操作 ====================

    private val baseDir: String
        get() = "/storage/emulated/0/Download/chajian/mingwuyan/"

    private fun readTxtFile(fileName: String): String {
        return try {
            val file = File(baseDir, fileName)
            if (file.exists()) file.readText() else ""
        } catch (e: Exception) {
            ""
        }
    }

    private fun writeTxtFile(fileName: String, content: String) {
        try {
            val file = File(baseDir, fileName)
            file.parentFile?.mkdirs()
            file.writeText(content)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun deleteFile(fileName: String) {
        try {
            val file = File(baseDir, fileName)
            if (file.exists()) file.delete()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
    }

    // ==================== RecyclerView 适配器 ====================

    inner class CharacterAdapter : RecyclerView.Adapter<CharacterAdapter.ViewHolder>() {

        private var onItemClick: ((Int) -> Unit)? = null
        private var onItemLongClick: ((Int) -> Unit)? = null
        private var onVoiceClick: ((Int) -> Unit)? = null

        fun setOnItemClickListener(listener: (Int) -> Unit) {
            onItemClick = listener
        }

        fun setOnItemLongClickListener(listener: (Int) -> Unit) {
            onItemLongClick = listener
        }

        fun setOnVoiceClickListener(listener: (Int) -> Unit) {
            onVoiceClick = listener
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = layoutInflater.inflate(R.layout.item_character, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            // position 是列表位置；只把列表位置传给 bind，真实索引统一在 bind 内换算
            val realIndex = if (position in displayList.indices) displayList[position] else -1
            val character = if (realIndex >= 0) characterRecords.optJSONObject(realIndex) else null
            holder.bind(character, position)
        }

        // 供外部根据列表位置取真实索引
        fun getRealIndex(position: Int): Int {
            return if (position in displayList.indices) displayList[position] else -1
        }

        override fun getItemCount(): Int = displayList.size

        inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val tvName: TextView = itemView.findViewById(R.id.tv_character_name)
            private val tvAliases: TextView = itemView.findViewById(R.id.tv_character_aliases)
            private val tvVoice: TextView = itemView.findViewById(R.id.tv_voice_info)
            private val ivFixed: ImageView = itemView.findViewById(R.id.iv_fixed_mark)

            fun bind(character: JSONObject?, position: Int) {
                if (character == null) return
                // position 参数为列表位置；换算成真实索引用于高亮判断
                val realIndex = if (position in displayList.indices) displayList[position] else -1

                val name = character.optString("name", "")
                val aliasesStr = character.optString("aliases", "")
                val voice = character.optString("voice", "")
                val usageCount = character.optInt("usageCount", 0)
                val fixedVoice = character.optBoolean("fixedVoice", false)
                val age = character.optString("age", "")

                // 主角皇冠放在主名字右边
                var displayName = name
                if (age == "主角") {
                    displayName += "👑"
                }
                // usageCount==50 时名字加【】（与JS保持一致）
                if (usageCount == 50) {
                    displayName = "【$displayName】"
                }
                tvName.text = displayName

                // 别名：格式为 (别名1|别名2|...)，排除主名字
                val aliasesList = aliasesStr.split("|").filter { it.trim() != name }.map { it.trim() }
                if (aliasesList.isNotEmpty()) {
                    tvAliases.visibility = View.VISIBLE
                    tvAliases.text = "(${aliasesList.joinToString("|")})"
                } else {
                    tvAliases.visibility = View.GONE
                }

                // 发音人：格式为【发音人名称】（usageCount==100 时发音人加【】）
                val voiceDisplay = if (usageCount == 100 && voice.isNotEmpty()) "【$voice】" else if (voice.isNotEmpty()) "【$voice】" else ""
                tvVoice.text = voiceDisplay

                // 固定标记 - 隐藏（不再显示✓）
                ivFixed.visibility = View.GONE

                // 背景颜色（适配深色模式）—— marked/selected 均以真实索引为准
                val isMarked = markedIndices.contains(realIndex)
                val isSelected = realIndex == selectedIndex
                val isDarkMode = (resources.configuration.uiMode and
                        android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                        android.content.res.Configuration.UI_MODE_NIGHT_YES

                val bgMark = if (isDarkMode) bgMarkedDark else bgMarked
                val bgSel = if (isDarkMode) bgOnlySelectedDark else bgOnlySelected
                val bgNormal = if (isDarkMode) bgNormalDark else Color.TRANSPARENT

                when {
                    isMarked && isSelected -> {
                        itemView.setBackgroundColor(bgMark)
                        tvName.setTextColor(colorSelected)
                    }
                    isMarked -> {
                        itemView.setBackgroundColor(bgMark)
                        tvName.setTextColor(colorMarked)
                    }
                    isSelected -> {
                        itemView.setBackgroundColor(bgSel)
                        tvName.setTextColor(colorOnlySelected)
                    }
                    else -> {
                        itemView.setBackgroundColor(bgNormal)
                        tvName.setTextColor(colorDefault)
                    }
                }

                // 点击回调传列表位置，真实索引由 setupRecyclerView 的 getRealIndex 统一换算
                itemView.setOnClickListener { onItemClick?.invoke(position) }
                itemView.setOnLongClickListener { onItemLongClick?.invoke(position); true }
                tvVoice.setOnClickListener { onVoiceClick?.invoke(position) }
                tvName.setOnClickListener { previewCharacterVoice(realIndex) }
            }
        }
    }
}
