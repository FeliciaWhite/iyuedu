package io.legado.app.ui.book.read.config

import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.databinding.DialogBgmDownloadBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.help.http.okHttpClient
import io.legado.app.service.BgmManager
import io.legado.app.utils.setLayout
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import splitties.init.appCtx
import java.io.File

class BgmDownloadDialog : BaseDialogFragment(R.layout.dialog_bgm_download) {

    private val binding by viewBinding(DialogBgmDownloadBinding::bind)
    private var isDownloadCancelled = false

    override fun onStart() {
        super.onStart()
        setLayout(0.85f, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        binding.btnCancel.setOnClickListener { onCancelClicked() }
        binding.btnDownload.setOnClickListener { startDownload() }
    }

    private fun onCancelClicked() {
        if (isDownloading()) {
            isDownloadCancelled = true
            binding.btnDownload.isEnabled = false
            binding.tvProgress.text = "正在取消，等待当前下载完成..."
        } else {
            dismiss()
        }
    }

    private fun isDownloading(): Boolean {
        return !binding.btnDownload.isEnabled && binding.btnDownload.text == "下载中..."
    }

    private fun startDownload() {
        val url = binding.etUrl.text?.toString()?.trim() ?: ""
        if (url.isEmpty()) {
            binding.etUrl.error = "网址不能为空"
            return
        }

        val bgmPath = AppConfig.bgmPath
        if (bgmPath.isNullOrBlank()) {
            toastOnUi("请先选择背景音乐文件夹")
            return
        }

        val repoInfo = parseRepoUrl(url)
        if (repoInfo == null) {
            toastOnUi("无法解析仓库网址")
            return
        }

        isDownloadCancelled = false
        binding.btnDownload.isEnabled = false
        binding.btnDownload.text = "下载中..."
        binding.tvProgress.visibility = View.VISIBLE
        binding.tvProgress.text = "正在获取文件列表..."

        lifecycleScope.launch(Dispatchers.IO) {
            val result = try {
                doDownload(repoInfo, bgmPath)
            } catch (e: Exception) {
                e.printStackTrace()
                Pair(0, 0)
            }
            withContext(Dispatchers.Main) {
                binding.btnDownload.isEnabled = true
                binding.btnDownload.text = "确定"
                val (total, success) = result
                val pathDesc = if (bgmPath.startsWith("content://")) {
                    "SAF目录"
                } else {
                    bgmPath
                }
                val msg = if (isDownloadCancelled) {
                    "已取消，共下载 $success / $total 个文件\n保存到：$pathDesc"
                } else {
                    "下载完成，共 $total 个文件，成功 $success 个\n保存到：$pathDesc"
                }
                binding.tvProgress.text = msg
                toastOnUi(msg)
                BgmManager.loadBgmFiles()
            }
        }
    }

    private suspend fun doDownload(
        repoInfo: RepoInfo,
        bgmPath: String
    ): Pair<Int, Int> = withContext(Dispatchers.IO) {
        val treeUrl =
            "https://cnb.cool/${repoInfo.org}/${repoInfo.repo}/-/tree/${repoInfo.branch}/${repoInfo.path}"
        val html = fetchHtml(treeUrl)
        val fileNames = parseAudioFileNames(html)

        if (fileNames.isEmpty()) {
            withContext(Dispatchers.Main) {
                binding.tvProgress.text = "未找到音频文件"
            }
            return@withContext Pair(0, 0)
        }

        var successCount = 0
        val total = fileNames.size

        for ((index, fileName) in fileNames.withIndex()) {
            if (isDownloadCancelled) {
                // 取消时：当前这个文件仍然下载完，后面的跳过
                break
            }
            val rawUrl =
                "https://cnb.cool/${repoInfo.org}/${repoInfo.repo}/-/git/raw/${repoInfo.branch}/${repoInfo.path}/${fileName}"
            withContext(Dispatchers.Main) {
                binding.tvProgress.text = "正在下载 (${index + 1}/$total)：$fileName"
            }
            try {
                val bytes = downloadFile(rawUrl)
                if (bytes != null && saveFile(bgmPath, fileName, bytes)) {
                    successCount++
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        Pair(total, successCount)
    }

    private fun fetchHtml(url: String): String {
        val request = Request.Builder().url(url).build()
        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
            return response.body.string()
        }
    }

    private fun parseAudioFileNames(html: String): List<String> {
        val names = mutableListOf<String>()
        val regex = """"name":"([^"]+\.(?:mp3|wav|ogg|flac|m4a|aac))"""".toRegex(RegexOption.IGNORE_CASE)
        regex.findAll(html).forEach { match ->
            val name = match.groupValues[1]
            if (!names.contains(name)) {
                names.add(name)
            }
        }
        return names
    }

    private fun downloadFile(url: String): ByteArray? {
        val request = Request.Builder().url(url).build()
        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            return response.body.bytes()
        }
    }

    private fun saveFile(bgmPath: String, fileName: String, bytes: ByteArray): Boolean {
        val mimeType = when {
            fileName.endsWith(".wav", true) -> "audio/wav"
            else -> "audio/mpeg"
        }
        return if (bgmPath.startsWith("content://")) {
            val docFile = DocumentFile.fromTreeUri(appCtx, Uri.parse(bgmPath))
            val existing = docFile?.findFile(fileName)
            existing?.delete()
            val newFile = docFile?.createFile(mimeType, fileName)
            newFile?.let {
                appCtx.contentResolver.openOutputStream(it.uri)?.use { out ->
                    out.write(bytes)
                    return true
                }
            }
            false
        } else {
            val dir = File(bgmPath)
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, fileName)
            file.writeBytes(bytes)
            true
        }
    }

    private fun parseRepoUrl(url: String): RepoInfo? {
        val regex =
            """https?://cnb\.cool/([^/]+)/([^/]+)(?:/-/tree/([^/]+)(?:/(.*))?)?""".toRegex()
        val match = regex.find(url) ?: return null
        val org = match.groupValues[1]
        val repo = match.groupValues[2]
        val branch = match.groupValues[3].ifEmpty { "bgm" }
        val path = match.groupValues[4].ifEmpty { "bgm" }
        return RepoInfo(org, repo, branch, path)
    }

    private data class RepoInfo(
        val org: String,
        val repo: String,
        val branch: String,
        val path: String
    )
}
