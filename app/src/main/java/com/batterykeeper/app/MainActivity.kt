package com.batterykeeper.app

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.QueryStats
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.batterykeeper.app.battery.BatteryMonitorService
import com.batterykeeper.app.settings.AppSettings
import com.batterykeeper.app.ui.BatteryViewModel
import com.batterykeeper.app.ui.screens.ChartsScreen
import com.batterykeeper.app.ui.screens.DashboardScreen
import com.batterykeeper.app.ui.screens.PowerScreen
import com.batterykeeper.app.ui.screens.ReportsScreen
import com.batterykeeper.app.ui.theme.BatteryKeeperTheme
import com.batterykeeper.app.ui.theme.Orange
import com.batterykeeper.app.ui.theme.ScreenBg
import com.batterykeeper.app.ui.theme.TxtTertiary

class MainActivity : ComponentActivity() {
    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val launchCommandState = mutableStateOf(LaunchCommand())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        launchCommandState.value = parseLaunchCommand(intent)
        if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        if (AppSettings(this).monitorEnabled) runCatching { BatteryMonitorService.start(this) }
        setContent {
            BatteryKeeperTheme {
                MainApp(launchCommandState.value)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        launchCommandState.value = parseLaunchCommand(intent)
    }

    private fun parseLaunchCommand(intent: Intent?): LaunchCommand {
        val action = intent?.action.orEmpty()
        return when (action) {
            ACTION_OPEN_POWER -> LaunchCommand(route = "power", token = System.nanoTime())
            ACTION_OPEN_REPORTS -> LaunchCommand(route = "reports", token = System.nanoTime())
            ACTION_IMPORT_IMAGE -> LaunchCommand(route = "reports", openImagePicker = true, token = System.nanoTime())
            else -> LaunchCommand()
        }
    }

    companion object {
        const val ACTION_OPEN_POWER = "com.batterykeeper.app.action.OPEN_POWER"
        const val ACTION_OPEN_REPORTS = "com.batterykeeper.app.action.OPEN_REPORTS"
        const val ACTION_IMPORT_IMAGE = "com.batterykeeper.app.action.IMPORT_IMAGE"
    }
}

data class LaunchCommand(
    val route: String? = null,
    val openImagePicker: Boolean = false,
    val token: Long = 0L,
)

private data class TabItem(val route: String, val label: String, val icon: ImageVector)

@Composable
fun MainApp(command: LaunchCommand = LaunchCommand()) {
    val navController = rememberNavController()
    val vm: BatteryViewModel = viewModel()
    var pendingImageImport by remember(command.token) { mutableStateOf(command.openImagePicker) }
    val tabs = listOf(
        TabItem("dashboard", "概览", Icons.Filled.Bolt),
        TabItem("power", "功率", Icons.Filled.Speed),
        TabItem("charts", "曲线", Icons.Filled.QueryStats),
        TabItem("reports", "报表", Icons.AutoMirrored.Filled.List),
    )

    LaunchedEffect(command.token) {
        command.route?.let { route ->
            navController.navigate(route) {
                popUpTo(navController.graph.startDestinationId) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        }
    }

    Scaffold(
        containerColor = ScreenBg,
        bottomBar = {
            NavigationBar(
                containerColor = Color(0xFF191D21),
                modifier = Modifier.height(60.dp),
                tonalElevation = 0.dp,
            ) {
                val current = navController.currentBackStackEntryAsState().value
                tabs.forEach { tab ->
                    NavigationBarItem(
                        selected = current?.destination?.route == tab.route,
                        onClick = {
                            navController.navigate(tab.route) {
                                popUpTo(navController.graph.startDestinationId) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(tab.icon, contentDescription = tab.label, modifier = Modifier.size(26.dp)) },
                        label = null,
                        alwaysShowLabel = false,
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Orange,
                            unselectedIconColor = TxtTertiary,
                            indicatorColor = Color(0x26FF8A3D),
                        ),
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = "dashboard",
            modifier = Modifier.padding(padding),
        ) {
            composable("dashboard") { DashboardScreen(vm) }
            composable("power") { PowerScreen(vm) }
            composable("charts") { ChartsScreen(vm) }
            composable("reports") {
                ReportsScreen(
                    vm = vm,
                    autoOpenImagePicker = pendingImageImport && command.route == "reports",
                    imagePickerRequestToken = command.token,
                    onAutoImagePickerConsumed = { pendingImageImport = false },
                )
            }
        }
    }
}
