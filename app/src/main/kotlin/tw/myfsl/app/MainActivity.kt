package tw.myfsl.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import tw.myfsl.app.ui.MyFslApp
import tw.myfsl.app.ui.theme.MyFslTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyFslTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MyFslApp()
                }
            }
        }
    }
}
