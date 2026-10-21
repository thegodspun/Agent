package io.github.thegodspun.sentinel

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import android.speech.RecognizerIntent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.mutableIntStateOf
import io.github.thegodspun.sentinel.ui.ChatViewModel
import io.github.thegodspun.sentinel.habits.UsageCollector
import io.github.thegodspun.sentinel.ui.SentinelScreen
import io.github.thegodspun.sentinel.ui.SentinelTheme

class MainActivity : ComponentActivity() {
    private val chat: ChatViewModel by viewModels()

    /** Bumped whenever a permission screen returns, so Setup re-checks what is granted. */
    private val refresh = mutableIntStateOf(0)

    private val voiceLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let(chat::send)
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh.intValue++ }

    private val settingsLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { refresh.intValue++ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SentinelTheme {
                SentinelScreen(
                    chat = chat,
                    refreshKey = refresh.intValue,
                    actions = actions,
                )
            }
        }
        handleVoiceExtra(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleVoiceExtra(intent)
    }

    override fun onResume() {
        super.onResume()
        refresh.intValue++
    }

    private fun handleVoiceExtra(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_VOICE, false) == true) {
            intent.removeExtra(EXTRA_VOICE)
            actions.listen()
        }
    }

    /** Things the screens ask the Activity to do. */
    val actions = object : Actions {
        override fun listen() {
            chat.stopSpeaking()
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PROMPT, "What do you need?")
            try {
                voiceLauncher.launch(intent)
            } catch (_: Exception) {
                chat.send("(Voice input isn't available on this phone.)")
            }
        }

        override fun requestPermissions() {
            permissionLauncher.launch(RUNTIME_PERMISSIONS)
        }

        override fun openNotificationAccess() =
            settingsLauncher.launch(Intent(AndroidSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS))

        override fun openAccessibility() =
            settingsLauncher.launch(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS))

        override fun openUsageAccess() = settingsLauncher.launch(UsageCollector.settingsIntent())

        override fun openAppInfo() = settingsLauncher.launch(
            Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
        )

        override fun requestBatteryExemption() {
            val pm = getSystemService(PowerManager::class.java)
            if (pm.isIgnoringBatteryOptimizations(packageName)) return
            @Suppress("BatteryLife") // Sentinel's purpose is to keep watch in the background.
            val intent = Intent(
                AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:$packageName"),
            )
            settingsLauncher.launch(intent)
        }

        override fun openUrl(url: String) {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }
    }

    interface Actions {
        fun listen()
        fun requestPermissions()
        fun openNotificationAccess()
        fun openAccessibility()
        fun openUsageAccess()
        fun openAppInfo()
        fun requestBatteryExemption()
        fun openUrl(url: String)
    }

    companion object {
        const val EXTRA_VOICE = "voice"

        val RUNTIME_PERMISSIONS = arrayOf(
            Manifest.permission.POST_NOTIFICATIONS,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.SEND_SMS,
            Manifest.permission.READ_SMS,
            Manifest.permission.READ_CALENDAR,
        )
    }
}
