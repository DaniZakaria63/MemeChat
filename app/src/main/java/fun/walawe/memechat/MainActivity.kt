package `fun`.walawe.memechat

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dagger.hilt.android.AndroidEntryPoint
import `fun`.walawe.constant.MODEL_DIR_NAME
import `fun`.walawe.constant.MODEL_DISPLAYNAME_EMBEDDING
import `fun`.walawe.constant.MODEL_DISPLAYNAME_MINICPM_LLM
import `fun`.walawe.constant.MODEL_DISPLAYNAME_MINICPM_MMPROJ
import `fun`.walawe.memechat.data.UserPreferences
import `fun`.walawe.memechat.model.Screen
import `fun`.walawe.memechat.ui.screen.AboutScreen
import `fun`.walawe.memechat.ui.screen.ChatScreen
import `fun`.walawe.memechat.ui.screen.OnboardingScreen
import `fun`.walawe.memechat.ui.screen.SettingsScreen
import `fun`.walawe.memechat.ui.theme.MemeChatAppTheme
import `fun`.walawe.memechat.ui.screen.DownloadScreen
import java.io.File
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var userPreferences: UserPreferences

    private lateinit var navHostController: NavHostController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
        )

        val onboardingCompleted = userPreferences.isOnboardingCompleted()
        val modelsExist = modelsExistOnDisk()
        val navigateToDownload = intent.getBooleanExtra(MemeChatApp.EXTRA_NAVIGATE_TO_DOWNLOAD, false)

        val startDest = when {
            navigateToDownload -> Screen.Download.route
            !onboardingCompleted -> Screen.Onboarding.route
            modelsExist -> Screen.Chat.route
            else -> Screen.Download.route
        }

        setContent {
            MemeChatAppTheme {
                navHostController = rememberNavController()
                NavHost(
                    navController = navHostController,
                    startDestination = startDest
                ) {
                    composable(Screen.Onboarding.route) {
                        OnboardingScreen {
                            navHostController.navigate(Screen.Download.route) {
                                popUpTo(0) { inclusive = true }
                            }
                        }
                    }
                    composable(Screen.Download.route) {
                        DownloadScreen {
                            navHostController.navigate(Screen.Chat.route) {
                                popUpTo(0) { inclusive = true }
                            }
                        }
                    }
                    composable(Screen.Chat.route) {
                        ChatScreen(
                            onOpenSettings = { navHostController.navigate(Screen.Settings.route) },
                            onOpenAbout = { navHostController.navigate(Screen.About.route) },
                        )
                    }
                    composable(Screen.Settings.route) {
                        SettingsScreen(onBack = { navHostController.popBackStack() })
                    }
                    composable(Screen.About.route) {
                        AboutScreen(onBack = { navHostController.popBackStack() })
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(MemeChatApp.EXTRA_NAVIGATE_TO_DOWNLOAD, false)) {
            if (::navHostController.isInitialized) {
                navHostController.navigate(Screen.Download.route) {
                    popUpTo(0) { inclusive = true }
                }
            }
        }
    }

    private fun modelsExistOnDisk(): Boolean {
        val dir = getDir(MODEL_DIR_NAME, MODE_PRIVATE)
        return listOf(
            MODEL_DISPLAYNAME_MINICPM_LLM,
            MODEL_DISPLAYNAME_MINICPM_MMPROJ,
            MODEL_DISPLAYNAME_EMBEDDING,
        ).all { File(dir, "$it.done").exists() }
    }
}
