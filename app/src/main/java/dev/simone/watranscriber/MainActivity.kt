package dev.simone.watranscriber

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.platform.LocalContext
import dev.simone.watranscriber.ui.MainScreen
import dev.simone.watranscriber.ui.MainViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val context = LocalContext.current
            val dark = isSystemInDarkTheme()
            val colors = when {
                Build.VERSION.SDK_INT < Build.VERSION_CODES.S ->
                    if (dark) darkColorScheme() else lightColorScheme()
                dark -> dynamicDarkColorScheme(context)
                else -> dynamicLightColorScheme(context)
            }
            MaterialTheme(colorScheme = colors) {
                MainScreen(
                    viewModel = viewModel,
                    onOpenStorageSettings = ::openStorageSettings,
                    onOpenNotificationSettings = ::openNotificationSettings,
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        viewModel.refresh()
    }

    override fun onStop() {
        super.onStop()
        viewModel.releaseModel()
    }

    private fun openStorageSettings() {
        startActivity(
            Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:$packageName"),
            )
        )
    }

    private fun openNotificationSettings() {
        startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }
}
