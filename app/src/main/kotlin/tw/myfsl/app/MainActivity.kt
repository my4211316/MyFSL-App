package tw.myfsl.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import dagger.hilt.android.AndroidEntryPoint
import tw.myfsl.app.core.data.DatabaseGuard
import tw.myfsl.app.ui.MyFslApp
import tw.myfsl.app.ui.start.NewerDataScreen
import tw.myfsl.app.ui.theme.MyFslTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 先檢查資料庫版本，再建立任何會打開資料庫的畫面。
        val newerData = DatabaseGuard.isNewerThanApp(this)
        setContent {
            MyFslTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    if (newerData) NewerDataScreen() else MyFslApp()
                }
            }
        }
    }
}
