package tw.myfsl.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import tw.myfsl.app.ui.checkin.CheckInScreen
import tw.myfsl.app.ui.checkin.CheckInViewModel
import tw.myfsl.app.ui.forecast.ForecastScreen
import tw.myfsl.app.ui.forecast.ForecastViewModel
import tw.myfsl.app.ui.forecast.ScenarioEditorForm
import tw.myfsl.app.ui.settings.SettingsScreen
import tw.myfsl.app.ui.settings.SettingsViewModel
import tw.myfsl.app.ui.period.PeriodScreen
import tw.myfsl.app.ui.period.PeriodViewModel
import tw.myfsl.app.ui.plan.PlanItemEditorForm
import tw.myfsl.app.ui.plan.PlanScreen
import tw.myfsl.app.ui.plan.PlanViewModel
import kotlinx.coroutines.launch
import tw.myfsl.app.ui.accounts.AccountsScreen
import tw.myfsl.app.ui.accounts.AccountsViewModel
import tw.myfsl.app.ui.entry.EntryScreen
import tw.myfsl.app.ui.entry.EntryViewModel
import tw.myfsl.app.ui.records.RecordEditorForm
import tw.myfsl.app.ui.records.RecordsScreen
import tw.myfsl.app.ui.records.RecordsViewModel
import tw.myfsl.app.ui.start.StartViewModel
import tw.myfsl.app.ui.start.WelcomeScreen
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

private enum class Tab(val route: String, val label: String, val icon: ImageVector) {
    ENTRY("entry", "記帳", Icons.Default.EditNote),
    PERIOD("period", "本期", Icons.Default.Home),
    PLAN("plan", "計畫", Icons.Default.TableChart),
    FORECAST("forecast", "試算", Icons.AutoMirrored.Filled.ShowChart),
    ACCOUNTS("accounts", "帳戶", Icons.Default.CreditCard),
}

@Composable
fun MyFslApp() {
    val navController = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.hierarchy?.mapNotNull { it.route }?.firstOrNull()

    // 第一次開啟：歡迎頁。選完之後要去的頁面在 NavHost 建好後才導過去。
    val startViewModel: StartViewModel = hiltViewModel()
    val needsWelcome by startViewModel.needsWelcome.collectAsStateWithLifecycle()
    var pendingRoute by rememberSaveable { mutableStateOf<String?>(null) }
    // 到期項目的起算日只在第一次設定；到期的款項由使用者在記帳畫面點下（R-DUE）。
    LaunchedEffect(Unit) { startViewModel.startDueTracking() }
    // 上次還原沒完成時先放回，完成前不開放帳務操作（F11）。
    val restoreState by startViewModel.restoreState.collectAsStateWithLifecycle()
    when (restoreState) {
        tw.myfsl.app.core.data.RestoreState.CHECKING -> {
            tw.myfsl.app.ui.start.CheckingDataScreen()
            return
        }
        tw.myfsl.app.core.data.RestoreState.RECOVERY_FAILED -> {
            tw.myfsl.app.ui.start.RecoveryFailedScreen(onRetry = startViewModel::retryRecovery)
            return
        }
        else -> Unit
    }
    when (needsWelcome) {
        null -> return
        true -> {
            WelcomeScreen(
                onStart = { pendingRoute = Tab.ACCOUNTS.route; startViewModel.finish() },
                onSample = startViewModel::useSample,
                onRestore = { pendingRoute = "settings"; startViewModel.finish() },
            )
            return
        }
        false -> Unit
    }
    val recoveryMessage by startViewModel.recoveryMessage.collectAsStateWithLifecycle()
    LaunchedEffect(recoveryMessage) {
        val message = recoveryMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message, withDismissAction = true, duration = androidx.compose.material3.SnackbarDuration.Long)
        startViewModel.dismissRecovery()
    }
    LaunchedEffect(pendingRoute) {
        val route = pendingRoute ?: return@LaunchedEffect
        navController.navigate(route) { launchSingleTop = true }
        pendingRoute = null
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState, modifier = Modifier.padding(bottom = 64.dp)) },
        bottomBar = {
            if (restoreState != tw.myfsl.app.core.data.RestoreState.RESTORING && Tab.entries.any { it.route == currentRoute }) {
                NavigationBar {
                    Tab.entries.forEach { tab ->
                        NavigationBarItem(
                            selected = currentRoute == tab.route,
                            onClick = {
                                navController.navigate(tab.route) {
                                    popUpTo(Tab.ENTRY.route)
                                    launchSingleTop = true
                                }
                            },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
      // 還原中：畫面蓋上一層不能操作的遮罩、返回鍵無效，直到還原結束（F11）。畫面本身不移除，發起還原的設定頁不會被中斷。
      val restoring = restoreState == tw.myfsl.app.core.data.RestoreState.RESTORING
      androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
        NavHost(
            navController = navController,
            startDestination = Tab.ENTRY.route,
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            composable(Tab.ENTRY.route) {
                val viewModel: EntryViewModel = hiltViewModel()
                val state by viewModel.state.collectAsStateWithLifecycle()

                LaunchedEffect(state.message) {
                    val message = state.message ?: return@LaunchedEffect
                    val result = snackbarHostState.showSnackbar(
                        message = message,
                        actionLabel = if (state.canUndo) "復原" else null,
                    )
                    if (result == SnackbarResult.ActionPerformed) viewModel.undo() else viewModel.dismissMessage()
                }

                EntryScreen(
                    state = state,
                    onSelectType = viewModel::selectType,
                    onSelectItem = viewModel::selectItem,
                    onSelectMethod = viewModel::selectMethod,
                    onSelectCard = viewModel::selectCard,
                    onToggleNote = viewModel::toggleNote,
                    onPress = viewModel::press,
                    onBackspace = viewModel::backspace,
                    onSave = viewModel::save,
                    onToggleShowAll = viewModel::toggleShowAll,
                    onToggleInstallment = viewModel::toggleInstallment,
                    onInstallmentMonths = viewModel::setInstallmentMonths,
                    onInstallmentFee = viewModel::setInstallmentFee,
                    onInstallmentFeeValue = viewModel::setInstallmentFeeValue,
                    onOpenRecords = { navController.navigate("records") },
                    onLoadSample = viewModel::loadSample,
                    onToggleRefund = viewModel::toggleRefund,
                    onAnswerMissed = viewModel::answerMissed,
                    onCancelMissed = viewModel::cancelMissed,
                    onOpenDue = viewModel::openDue,
                    onCloseDue = viewModel::closeDue,
                    onDueAmount = viewModel::setDueAmount,
                    onDueMethod = viewModel::setDueMethod,
                    onDueCard = viewModel::setDueCard,
                    onDueAccount = viewModel::setDueAccount,
                    onDueUseDueDate = viewModel::setDueUseDueDate,
                    onRecordDue = viewModel::recordDue,
                    onSkipDue = viewModel::skipDue,
                )
            }

            composable("records") {
                val viewModel: RecordsViewModel = hiltViewModel()
                val state by viewModel.state.collectAsStateWithLifecycle()
                LaunchedEffect(state.message) {
                    val message = state.message ?: return@LaunchedEffect
                    snackbarHostState.showSnackbar(message)
                    viewModel.dismissMessage()
                }
                BackHandler(enabled = state.editor != null) { viewModel.cancelEdit() }
                RecordsScreen(
                    state = state,
                    onBack = { navController.popBackStack() },
                    onPrevious = viewModel::previousMonth,
                    onNext = viewModel::nextMonth,
                    onFilter = viewModel::setFilter,
                    onDelete = viewModel::delete,
                    onEdit = viewModel::edit,
                    editorContent = { editor ->
                        RecordEditorForm(
                            editor = editor,
                            today = state.today,
                            items = state.items,
                            cards = state.cards,
                            pickCard = state.pickCard,
                            onChange = viewModel::change,
                            onSave = viewModel::saveEdit,
                            onCancel = viewModel::cancelEdit,
                        )
                    },
                )
            }

            composable(Tab.PERIOD.route) {
                val viewModel: PeriodViewModel = hiltViewModel()
                val state by viewModel.state.collectAsStateWithLifecycle()
                PeriodScreen(
                    state = state,
                    onStartCheckIn = { navController.navigate("checkin") },
                    onOpenForecast = {
                        navController.navigate(Tab.FORECAST.route) {
                            popUpTo(Tab.ENTRY.route)
                            launchSingleTop = true
                        }
                    },
                    onToggleShowAll = viewModel::toggleShowAll,
                    onOpenSettings = { navController.navigate("settings") },
                )
            }

            composable("settings") {
                val viewModel: SettingsViewModel = hiltViewModel()
                val state by viewModel.state.collectAsStateWithLifecycle()
                LaunchedEffect(state.message) {
                    val message = state.message ?: return@LaunchedEffect
                    snackbarHostState.showSnackbar(message)
                    viewModel.dismissMessage()
                }
                val context = LocalContext.current
                val scope = rememberCoroutineScope()
                val saveBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
                    if (uri == null) return@rememberLauncherForActivityResult
                    scope.launch {
                        runCatching {
                            val bytes = viewModel.backupBytes()
                            context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("無法寫入")
                        }.onSuccess { viewModel.backupSaved() }
                            .onFailure { viewModel.readError("備份失敗：${it.message}") }
                    }
                }
                val openBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                    if (uri == null) return@rememberLauncherForActivityResult
                    runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0) }
                        .onSuccess(viewModel::onBackupLoaded)
                        .onFailure { viewModel.readError("讀不到檔案：${it.message}") }
                }
                SettingsScreen(
                    state = state,
                    onBack = { navController.popBackStack() },
                    onChange = viewModel::change,
                    onSave = viewModel::save,
                    onLoadSample = viewModel::loadSample,
                    onClearAll = viewModel::clearAll,
                    onExportBackup = {
                        val today = java.time.LocalDate.now()
                        saveBackup.launch("MyFSL-備份-%d%02d%02d.json".format(today.year, today.monthValue, today.dayOfMonth))
                    },
                    onImportBackup = { openBackup.launch(arrayOf("application/json", "application/octet-stream", "text/*")) },
                    onConfirmRestore = viewModel::confirmRestore,
                    onCancelRestore = viewModel::cancelRestore,
                    onClearCrashLog = viewModel::clearCrashLog,
                )
            }

            composable("checkin") {
                val viewModel: CheckInViewModel = hiltViewModel()
                val state by viewModel.state.collectAsStateWithLifecycle()
                BackHandler { if (state.done || !viewModel.back()) navController.popBackStack() }
                CheckInScreen(
                    state = state,
                    onClose = { navController.popBackStack() },
                    onStep = viewModel::goTo,
                    onNext = viewModel::next,
                    onChoose = viewModel::choose,
                    onConfirmAmount = viewModel::setConfirmAmount,
                    onReport = viewModel::setReport,
                    onDueChoose = viewModel::chooseDue,
                    onDueAmount = viewModel::setDueAmount,
                    onBalance = viewModel::setBalance,
                    onMatch = viewModel::matchComputed,
                    onResolution = viewModel::setResolution,
                    onItem = viewModel::setItem,
                    onFinish = viewModel::finish,
                    onAgain = viewModel::again,
                )
            }

            composable(Tab.PLAN.route) {
                val viewModel: PlanViewModel = hiltViewModel()
                val state by viewModel.state.collectAsStateWithLifecycle()
                val context = LocalContext.current
                val scope = rememberCoroutineScope()

                LaunchedEffect(state.message) {
                    val message = state.message ?: return@LaunchedEffect
                    snackbarHostState.showSnackbar(message)
                    viewModel.dismissMessage()
                }
                BackHandler(enabled = state.import != null) { viewModel.cancelImport() }
                BackHandler(enabled = state.editor != null) { viewModel.cancelEdit() }

                val openCsv = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                    if (uri == null) return@rememberLauncherForActivityResult
                    runCatching {
                        val name = context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
                        } ?: "匯入檔案"
                        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0)
                        viewModel.onFileLoaded(name, bytes)
                    }.onFailure { viewModel.readError("讀不到檔案：${it.message}") }
                }
                val saveExport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
                    if (uri == null) return@rememberLauncherForActivityResult
                    scope.launch {
                        runCatching {
                            val bytes = viewModel.exportBytes()
                            context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                        }.onSuccess { viewModel.exported("匯出的計畫") }
                            .onFailure { viewModel.readError("存檔失敗：${it.message}") }
                    }
                }
                val saveTemplate = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
                    if (uri == null) return@rememberLauncherForActivityResult
                    runCatching {
                        context.contentResolver.openOutputStream(uri)?.use { it.write(viewModel.templateBytes()) }
                    }.onSuccess { viewModel.exported("範本") }
                        .onFailure { viewModel.readError("存檔失敗：${it.message}") }
                }

                PlanScreen(
                    state = state,
                    onPreviousYear = viewModel::previousYear,
                    onNextYear = viewModel::nextYear,
                    onImport = { openCsv.launch(arrayOf("text/*", "application/vnd.ms-excel", "application/octet-stream")) },
                    onExport = { saveExport.launch("MyFSL-計畫-${state.year}.csv") },
                    onTemplate = { saveTemplate.launch("MyFSL-計畫範本.csv") },
                    onImportMode = viewModel::setImportMode,
                    onConfirmImport = viewModel::confirmImport,
                    onCancelImport = viewModel::cancelImport,
                    onAddItem = viewModel::startNew,
                    onEditItem = viewModel::edit,
                    onShowTable = viewModel::setShowTable,
                    editorContent = { editor ->
                        PlanItemEditorForm(
                            editor = editor,
                            year = state.year,
                            groups = state.groups,
                            accounts = state.accounts,
                            onChange = viewModel::change,
                            onType = viewModel::setType,
                            onAddLine = viewModel::addLine,
                            onRemoveLine = viewModel::removeLine,
                            onMethod = viewModel::setMethod,
                            onMonth = viewModel::setMonth,
                            onToggleLine = viewModel::toggleLine,
                            onQuickFill = viewModel::quickFill,
                            onSave = viewModel::saveItem,
                            onCancel = viewModel::cancelEdit,
                            onArchive = viewModel::archiveItem,
                        )
                    },
                )
            }

            composable(Tab.FORECAST.route) {
                val viewModel: ForecastViewModel = hiltViewModel()
                val state by viewModel.state.collectAsStateWithLifecycle()

                LaunchedEffect(state.message) {
                    val message = state.message ?: return@LaunchedEffect
                    snackbarHostState.showSnackbar(message)
                    viewModel.dismissMessage()
                }
                BackHandler(enabled = state.editor != null) { viewModel.cancelEdit() }

                ForecastScreen(
                    state = state,
                    onMonths = viewModel::setMonths,
                    onToggleVisible = viewModel::toggleVisible,
                    onAdd = viewModel::startNew,
                    onEdit = viewModel::edit,
                    onToggleSeek = viewModel::toggleSeek,
                    onSeekTarget = viewModel::setSeekTarget,
                    onSeekItem = viewModel::toggleSeekItem,
                    onRunSeek = viewModel::runSeek,
                    onSaveSeek = viewModel::saveSeekAsScenario,
                    editorContent = { editor ->
                        ScenarioEditorForm(
                            editor = editor,
                            today = state.today,
                            accounts = state.accounts,
                            expenseItems = state.expenseItems,
                            onDraft = viewModel::changeDraft,
                            onAddChange = viewModel::addChange,
                            onChange = viewModel::updateChange,
                            onRemoveChange = viewModel::removeChange,
                            onSave = viewModel::saveScenario,
                            onCancel = viewModel::cancelEdit,
                            onDelete = viewModel::deleteScenario,
                        )
                    },
                )
            }

            composable(Tab.ACCOUNTS.route) {
                val viewModel: AccountsViewModel = hiltViewModel()
                val state by viewModel.state.collectAsStateWithLifecycle()

                LaunchedEffect(state.message) {
                    val message = state.message ?: return@LaunchedEffect
                    snackbarHostState.showSnackbar(message)
                    viewModel.dismissMessage()
                }
                BackHandler(enabled = state.editor != null) { viewModel.cancel() }

                AccountsScreen(
                    state = state,
                    onAdd = { viewModel.startNew() },
                    onEdit = viewModel::edit,
                    onChange = viewModel::update,
                    onToggleAdvanced = viewModel::toggleAdvanced,
                    onSave = viewModel::save,
                    onCancel = viewModel::cancel,
                    onArchive = viewModel::archive,
                )
            }
        }
        if (restoring) {
            // 放在所有頁面之後組合：比頁面自己的返回處理優先，返回鍵在還原中完全無效。
            BackHandler {}
            tw.myfsl.app.ui.start.RestoringOverlay()
        }
      }
    }
}
