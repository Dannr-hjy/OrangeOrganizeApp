package com.dannr.chengzikb

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.dannr.chengzikb.ui.nav.AppNavHost
import com.dannr.chengzikb.ui.theme.OrangeKeBiaoTheme
import com.dannr.chengzikb.ui.theme.ThemeMode
import com.dannr.chengzikb.ui.theme.parseHexColor

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            AppRoot()
        }
    }
}

/**
 * 根主题：读取设置中持久化的主题模式（跟随系统/浅/深），未加载时默认跟随系统。
 */
@Composable
private fun AppRoot() {
    val context = LocalContext.current
    val app = context.applicationContext as OrangeApp
    val settings by app.container.settingsRepository.settings.collectAsState(initial = null)
    val themeMode = when (settings?.themeMode) {
        1 -> ThemeMode.LIGHT
        2 -> ThemeMode.DARK
        else -> ThemeMode.SYSTEM
    }
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    // 状态栏/导航栏图标深浅跟随所选主题（edge-to-edge 下不自动跟随强制主题）
    val view = LocalView.current
    if (!view.isInEditMode) {
        DisposableEffect(dark) {
            val window = (view.context as Activity).window
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !dark
            controller.isAppearanceLightNavigationBars = !dark
            onDispose {}
        }
    }
    val seedColor = settings?.themeSeedHex?.let { parseHexColor(it) }
    OrangeKeBiaoTheme(themeMode = themeMode, seedColor = seedColor) {
        // 用主题 background 铺满窗口：避免未被组件覆盖处露出系统窗口的浅色底
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            AppNavHost()
        }
    }
}
