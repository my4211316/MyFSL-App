package tw.myfsl.app.ui.start

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import tw.myfsl.app.core.data.FinanceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 第一次開啟：還沒看過歡迎頁，而且沒有任何帳戶與計畫。null = 還在讀取。 */
@HiltViewModel
class StartViewModel @Inject constructor(
    private val repository: FinanceRepository,
) : ViewModel() {

    val needsWelcome: StateFlow<Boolean?> = repository.snapshot
        .map { !it.settings.onboarded && it.accounts.isEmpty() && it.items.isEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun finish(then: () -> Unit = {}) {
        viewModelScope.launch {
            repository.setOnboarded()
            then()
        }
    }

    fun useSample() {
        viewModelScope.launch {
            repository.installSample()
            repository.setOnboarded()
        }
    }

    /** 第一次開啟時設定到期項目的起算日（R-DUE）；不會自動記任何帳。 */
    fun startDueTracking() {
        viewModelScope.launch { runCatching { repository.startDueTracking() } }
    }
}

/** 手機上的資料是較新版 App 寫的：不打開、不清除，請使用者裝回新版（R-DATA-02）。 */
@Composable
fun NewerDataScreen(modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.height(32.dp))
        Text("這支手機上的資料比這個版本新", style = MaterialTheme.typography.headlineSmall)
        Text(
            "你的帳務資料是用較新版的 MyFSL 存的，這個版本讀不懂。為了不弄壞資料，App 不會打開它，也不會清除它。",
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            "請安裝最新版的 MyFSL（不要先解除安裝，解除安裝會刪掉資料），資料會原封不動地回來。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun WelcomeScreen(
    onStart: () -> Unit,
    onSample: () -> Unit,
    onRestore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.height(24.dp))
        Text("歡迎使用 MyFSL", style = MaterialTheme.typography.headlineMedium)
        Text(
            "用年度計畫控管每月現金流：記帳花多少、每週對帳補漏、看未來的現金水位會不會低於安全線。",
            style = MaterialTheme.typography.bodyLarge,
        )

        Step(1, "建立帳戶", "錢包、薪轉帳戶、信用卡、貸款。只要名稱和目前餘額，其他都可以之後再填。")
        Step(2, "建立年度計畫", "在「計畫」匯入 Excel 另存的 CSV，或一個一個新增項目，並設定每一列用現金還是刷卡。")
        Step(3, "每天記帳、每週檢查", "打開 App 直接記一筆；每週一次對錢包、銀行與卡片的金額，補上漏記。")

        Text(
            "App 不連網、不會上傳你的資料（若手機開啟 Google 備份，系統會一併加密備份）。記得定期到「設定」匯出完整備份。試算結果只是依你輸入的數字推算，不是財務建議。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) { Text("開始設定帳戶") }
        OutlinedButton(onClick = onSample, modifier = Modifier.fillMaxWidth()) { Text("先用示意資料看看") }
        TextButton(onClick = onRestore, modifier = Modifier.fillMaxWidth()) { Text("我有備份檔，要還原") }
    }
}

@Composable
private fun Step(number: Int, title: String, body: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(32.dp)) {
            Text(
                "$number",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.padding(top = 4.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
