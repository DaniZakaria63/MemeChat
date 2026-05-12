package `fun`.walawe.memechat

import android.app.Activity
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
import `fun`.walawe.memechat.model.Screen
import `fun`.walawe.memechat.ui.screen.ChatScreen
import `fun`.walawe.memechat.ui.screen.DownloadScreen
import `fun`.walawe.memechat.ui.screen.SettingsScreen
import `fun`.walawe.memechat.ui.theme.MemeChatAppTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge(statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT))
        setContent{
            MemeChatAppTheme {
                val navHostController = rememberNavController()
                NavHost(
                    navController = navHostController,
                    startDestination = Screen.Download.route
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
}
