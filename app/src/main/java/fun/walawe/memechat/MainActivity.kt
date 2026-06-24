package `fun`.walawe.memechat

import android.app.Activity
import android.content.Context
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
import `fun`.walawe.constant.MODEL_DISPLAYNAME_EMBEDDING
import `fun`.walawe.constant.MODEL_DISPLAYNAME_MINICPM_LLM
import `fun`.walawe.constant.MODEL_DISPLAYNAME_MINICPM_MMPROJ
import `fun`.walawe.memechat.model.Screen
import `fun`.walawe.memechat.ui.screen.ChatScreen
import `fun`.walawe.memechat.ui.screen.DownloadScreen
import `fun`.walawe.memechat.ui.screen.SettingsScreen
import `fun`.walawe.memechat.ui.theme.MemeChatAppTheme
import java.io.File

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val startDest = if (modelsExistOnDisk(this)) Screen.Chat.route else Screen.Download.route

        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        setContent{
            MemeChatAppTheme {
                val navHostController = rememberNavController()
                NavHost(
                    navController = navHostController,
                    startDestination = startDest
                ){
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

    private fun modelsExistOnDisk(context: Context): Boolean {
        val dir = context.getDir(MODEL_DIR_NAME, Context.MODE_PRIVATE)
        return listOf(
            MODEL_DISPLAYNAME_MINICPM_LLM,
            MODEL_DISPLAYNAME_MINICPM_MMPROJ,
            MODEL_DISPLAYNAME_EMBEDDING,
        ).all { File(dir, "$it.done").exists() }
    }

}
