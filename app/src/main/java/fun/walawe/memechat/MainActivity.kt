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
                    startDestination = "Downloader"
                ){
                    composable("Downloader") {
                        DownloadScreen {
                            navHostController.navigate("Chat") {
                                popUpTo("Downloader") { inclusive = true }
                            }
                        }
                    }
                    composable("Chat") {
                        ChatScreen(onOpenSettings = { navHostController.navigate("Settings") })
                    }
                    composable("Settings") {
                        SettingsScreen(onBack = { navHostController.popBackStack() })
                    }
                }
            }
        }
    }
}
