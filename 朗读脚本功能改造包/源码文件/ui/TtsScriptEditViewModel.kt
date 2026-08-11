package io.legado.app.ui.tts.script

import android.app.Application
import android.content.Intent
import io.legado.app.base.BaseViewModel
import io.legado.app.data.appDb
import io.legado.app.data.entities.TtsScript
import io.legado.app.exception.NoStackTraceException
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.getClipText
import io.legado.app.utils.printOnDebug
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers

class TtsScriptEditViewModel(application: Application) : BaseViewModel(application) {

    var ttsScript: TtsScript? = null

    fun initData(intent: Intent, finally: (ttsScript: TtsScript) -> Unit) {
        execute {
            val id = intent.getLongExtra("id", -1)
            ttsScript = if (id > 0) {
                appDb.ttsScriptDao.findById(id)
            } else {
                TtsScript(name = "")
            }
        }.onFinally {
            ttsScript?.let {
                finally(it)
            }
        }
    }

    fun pasteScript(success: (TtsScript) -> Unit) {
        execute(context = Dispatchers.Main) {
            val text = context.getClipText()
            if (text.isNullOrBlank()) {
                throw NoStackTraceException("剪贴板为空")
            }
            GSON.fromJsonObject<TtsScript>(text).getOrNull()
                ?: throw NoStackTraceException("格式不对")
        }.onSuccess {
            success.invoke(it)
        }.onError {
            context.toastOnUi(it.localizedMessage ?: "Error")
            it.printOnDebug()
        }
    }

    fun save(ttsScript: TtsScript, success: () -> Unit) {
        execute {
            if (ttsScript.order == Int.MIN_VALUE) {
                ttsScript.order = appDb.ttsScriptDao.maxOrder + 1
            }
            appDb.ttsScriptDao.insert(ttsScript)
        }.onSuccess {
            success()
        }.onError {
            context.toastOnUi("save error, ${it.localizedMessage}")
        }
    }
}
