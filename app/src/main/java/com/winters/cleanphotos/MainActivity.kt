package com.winters.cleanphotos

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.winters.cleanphotos.core.navigation.NavGraph
import com.winters.cleanphotos.core.design.CleanphotosTheme
import dagger.hilt.android.AndroidEntryPoint

/** AppCompatActivity：AppCompatDelegate.setApplicationLocales 在 Android 8–12 只对它生效（切换后重建，界面状态由 ViewModel / 导航保存）。 */
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CleanphotosTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    NavGraph()
                }
            }
        }
    }
}
