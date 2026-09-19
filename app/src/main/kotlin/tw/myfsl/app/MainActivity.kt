package tw.myfsl.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import tw.myfsl.app.core.data.DatabaseGuard
import tw.myfsl.app.ui.DeepLinks
import tw.myfsl.app.ui.MyFslApp
import tw.myfsl.app.ui.start.NewerDataScreen
import tw.myfsl.app.ui.theme.MyFslTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 先檢查資料庫版本，再建立任何會打開資料庫的畫面。
        val newerData = DatabaseGuard.isNewerThanApp(this)
        handle(intent)
        if (!newerData) requestNotificationsIfNeeded()
        setContent {
            MyFslTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    if (newerData) NewerDataScreen() else MyFslApp()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    /** 點結帳提醒打開 App：直接打開那張卡的帳單校正（R-REM-01）。 */
    private fun handle(intent: Intent?) {
        val cardId = intent?.getLongExtra(EXTRA_OPEN_BILL, -1L) ?: -1L
        if (cardId > 0) {
            DeepLinks.openBill(cardId)
            intent?.removeExtra(EXTRA_OPEN_BILL)
        }
    }

    /** Android 13 起通知要使用者允許；沒允許時提醒只會在 App 裡的本月到期顯示。 */
    private fun requestNotificationsIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    companion object {
        const val EXTRA_OPEN_BILL = "tw.myfsl.app.extra.OPEN_BILL"
    }
}
