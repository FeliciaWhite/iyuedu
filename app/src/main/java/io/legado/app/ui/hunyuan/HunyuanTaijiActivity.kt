package io.legado.app.ui.hunyuan

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import io.legado.app.help.hunyuan.App
import io.legado.app.help.hunyuan.ProxyViewModel

class HunyuanTaijiActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val vm = ProxyViewModel(application)
        setContent {
            val darkTheme = isSystemInDarkTheme()
            val colorScheme = if (darkTheme) darkColorScheme() else lightColorScheme()
            MaterialTheme(colorScheme = colorScheme) {
                App(vm)
            }
        }
    }
}
