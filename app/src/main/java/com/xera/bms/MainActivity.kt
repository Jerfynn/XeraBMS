package com.xera.bms

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xera.bms.ui.AppRoot
import com.xera.bms.ui.MainViewModel
import com.xera.bms.ui.theme.XeraTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val vm: MainViewModel = viewModel()
            val settings = vm.settings.collectAsStateWithLifecycle().value
            val dark = when (settings.theme) {
                1 -> true
                2 -> false
                else -> isSystemInDarkTheme()
            }
            XeraTheme(dark = dark) {
                AppRoot(vm)
            }
        }
    }
}
