package `fun`.walawe.memechat

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dagger.hilt.android.AndroidEntryPoint
import `fun`.walawe.constant.MODEL_DIR_NAME
import `fun`.walawe.memechat.data.UserPreferences
import javax.inject.Inject
import `fun`.walawe.constant.MODEL_DISPLAYNAME_EMBEDDING
import `fun`.walawe.constant.MODEL_DISPLAYNAME_MINICPM_LLM
import `fun`.walawe.constant.MODEL_DISPLAYNAME_MINICPM_MMPROJ
import `fun`.walawe.memechat.model.ONBOARDING_COMPLETED_KEY
import `fun`.walawe.memechat.model.ONBOARDING_PREFS
import `fun`.walawe.memechat.model.Screen
import `fun`.walawe.memechat.ui.screen.ChatScreen
import `fun`.walawe.memechat.ui.screen.OnboardingScreen
import `fun`.walawe.memechat.ui.screen.SettingsScreen
import `fun`.walawe.memechat.ui.theme.MemeChatAppTheme
import `fun`.walawe.memechat.ui.screen.DownloadScreen
import java.io.File

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var userPreferences: UserPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
        )

        val onboardingCompleted = userPreferences.isOnboardingCompleted()
        val modelsExist = modelsExistOnDisk()

        val startDest = when {
            !onboardingCompleted -> Screen.Onboarding.route
            modelsExist -> Screen.Chat.route
            else -> Screen.Download.route
        }

        setContent{
            MemeChatAppTheme {
                val navHostController = rememberNavController()
                NavHost(
                    navController = navHostController,
                    startDestination = startDest
                ){
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
                        ChatScreen(onOpenSettings = { navHostController.navigate("Settings") })
                    }
                    composable(Screen.Settings.route) {
                        SettingsScreen(onBack = { navHostController.popBackStack() })
                    }
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
