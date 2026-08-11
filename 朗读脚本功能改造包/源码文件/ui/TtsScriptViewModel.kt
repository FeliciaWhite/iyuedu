package io.legado.app.ui.tts.script

import android.app.Application
import io.legado.app.base.BaseViewModel
import io.legado.app.data.appDb
import io.legado.app.data.entities.TtsScript

class TtsScriptViewModel(application: Application) : BaseViewModel(application) {

    fun update(vararg script: TtsScript) {
        execute {
            appDb.ttsScriptDao.update(*script)
        }
    }

    fun delete(script: TtsScript) {
        execute {
            appDb.ttsScriptDao.delete(script)
        }
    }

    fun toTop(script: TtsScript) {
        execute {
            script.order = appDb.ttsScriptDao.minOrder - 1
            appDb.ttsScriptDao.update(script)
        }
    }

    fun toBottom(script: TtsScript) {
        execute {
            script.order = appDb.ttsScriptDao.maxOrder + 1
            appDb.ttsScriptDao.update(script)
        }
    }

    fun topSelect(scripts: List<TtsScript>) {
        execute {
            var minOrder = appDb.ttsScriptDao.minOrder - scripts.size
            scripts.forEach {
                it.order = ++minOrder
            }
            appDb.ttsScriptDao.update(*scripts.toTypedArray())
        }
    }

    fun bottomSelect(scripts: List<TtsScript>) {
        execute {
            var maxOrder = appDb.ttsScriptDao.maxOrder
            scripts.forEach {
                it.order = maxOrder++
            }
            appDb.ttsScriptDao.update(*scripts.toTypedArray())
        }
    }

    fun upOrder() {
        execute {
            val scripts = appDb.ttsScriptDao.all
            for ((index, script) in scripts.withIndex()) {
                script.order = index + 1
            }
            appDb.ttsScriptDao.update(*scripts.toTypedArray())
        }
    }

    fun enableSelection(scripts: List<TtsScript>) {
        execute {
            val array = Array(scripts.size) {
                scripts[it].copy(isEnabled = true)
            }
            appDb.ttsScriptDao.update(*array)
        }
    }

    fun disableSelection(scripts: List<TtsScript>) {
        execute {
            val array = Array(scripts.size) {
                scripts[it].copy(isEnabled = false)
            }
            appDb.ttsScriptDao.update(*array)
        }
    }

    fun delSelection(scripts: List<TtsScript>) {
        execute {
            appDb.ttsScriptDao.delete(*scripts.toTypedArray())
        }
    }
}
