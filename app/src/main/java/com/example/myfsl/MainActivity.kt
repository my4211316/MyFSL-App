package com.example.myfsl

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.myfsl.ui.theme.MyFSLTheme

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MyFSLTheme {
                MainContent(viewModel = viewModel)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainContent(viewModel: MainViewModel) {
    val navController = rememberNavController()

    Scaffold(
        bottomBar = {
            NavigationBar {
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentDestination = navBackStackEntry?.destination

                val items = listOf(
                    BottomNavItem("dashboard", "儀表板", Icons.Filled.Dashboard),
                    BottomNavItem("entry", "記帳", Icons.Filled.Add),
                    BottomNavItem("transactions", "交易", Icons.Filled.List),
                    BottomNavItem("budgets", "預算", Icons.Filled.AccountBalance),
                    BottomNavItem("settings", "設定", Icons.Filled.Settings)
                )

                items.forEach { item ->
                    NavigationBarItem(
                        icon = { Icon(item.icon, contentDescription = item.title) },
                        label = { Text(item.title) },
                        selected = currentDestination?.hierarchy?.any { it.route == item.route } == true,
                        onClick = {
                            navController.navigate(item.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = "dashboard",
            modifier = Modifier.padding(innerPadding)
        ) {
            composable("dashboard") {
                DashboardScreen(viewModel = viewModel)
            }
            composable("entry") {
                TransactionEntryScreen(
                    vm = viewModel,
                    onNavigateBack = {
                        navController.navigate("dashboard") {
                            popUpTo("dashboard") { inclusive = true }
                        }
                    }
                )
            }
            composable("transactions") {
                TransactionListScreen(vm = viewModel)
            }
            composable("budgets") {
                BudgetListScreen(
                    viewModel = viewModel,
                    onNavigateToBudgetDetail = { budgetId ->
                        navController.navigate("budget_detail/$budgetId")
                    }
                )
            }
            composable("budget_detail/{budgetId}") { backStackEntry ->
                val budgetId = backStackEntry.arguments?.getString("budgetId") ?: ""
                BudgetDetailScreen(
                    viewModel = viewModel,
                    budgetId = budgetId,
                    onNavigateBack = { navController.popBackStack() }
                )
            }
            composable("settings") {
                SettingsScreen(viewModel = viewModel)
            }
        }
    }
}

data class BottomNavItem(
    val route: String,
    val title: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector
)

@Preview(showBackground = true)
@Composable
fun MainContentPreview() {
    MyFSLTheme {
        // Preview content
    }
}