package com.example.myfsl

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.List
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.myfsl.ui.theme.MyFSLTheme

// 步驟一：定義我們App中所有的畫面路徑、標籤和圖示
sealed class Screen(val route: String, val label: String, val icon: ImageVector) {
    object Dashboard : Screen("dashboard", "儀表板", Icons.Default.Home)
    object Entry : Screen("entry", "記帳", Icons.Default.Edit)
    object List : Screen("list", "列表", Icons.Default.List)
}

// 這是您App的主入口點
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MyFSLTheme {
                MainApp()
            }
        }
    }
}

// 步驟二：建立App的主體，包含底部導覽列
@Composable
fun MainApp() {
    val navController = rememberNavController()
    // 定義要顯示在導覽列上的畫面列表
    val screens = listOf(
        Screen.Dashboard,
        Screen.Entry,
        Screen.List
    )

    Scaffold(
        bottomBar = {
            NavigationBar {
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentRoute = navBackStackEntry?.destination?.route

                // 根據 screens 列表，自動產生底部按鈕
                screens.forEach { screen ->
                    NavigationBarItem(
                        icon = { Icon(screen.icon, contentDescription = screen.label) },
                        label = { Text(screen.label) },
                        selected = currentRoute == screen.route,
                        onClick = {
                            navController.navigate(screen.route) {
                                // 導航到堆疊頂部，避免重複建立畫面
                                popUpTo(navController.graph.startDestinationId) {
                                    saveState = true
                                }
                                // 避免在堆疊頂部重複建立同一個畫面
                                launchSingleTop = true
                                // 重新選擇已選中的項目時，恢復其狀態
                                restoreState = true
                            }
                        }
                    )
                }
            }
        }
    ) { innerPadding ->
        // 步驟三：設定畫面的容器與導航路徑
        AppNavHost(navController = navController, modifier = Modifier.padding(innerPadding))
    }
}

// 步驟四：App的導航路徑設定
@Composable
fun AppNavHost(navController: NavHostController, modifier: Modifier = Modifier) {
    // 【關鍵】在這裡建立共用的ViewModel實例，讓所有畫面共享同一個大腦
    val mainViewModel: MainViewModel = viewModel()

    NavHost(
        navController = navController,
        startDestination = Screen.Dashboard.route, // 讓App一打開就是儀表板頁面
        modifier = modifier
    ) {
        // 定義每一條路徑對應到哪一個畫面
        composable(Screen.Dashboard.route) {
            DashboardScreen(vm = mainViewModel) // 傳入共用的ViewModel
        }
        composable(Screen.Entry.route) {
            TransactionEntryScreen(vm = mainViewModel) // 傳入共用的ViewModel
        }
        composable(Screen.List.route) {
            TransactionListScreen(vm = mainViewModel) // 傳入共用的ViewModel
        }
    }
}