package com.shashank.hostterminal

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.outlined.AdminPanelSettings
import androidx.compose.material.icons.outlined.CloudQueue
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.shashank.hostterminal.data.ActiveServerState
import com.shashank.hostterminal.data.AdminPolicyManager
import com.shashank.hostterminal.runtime.CloudflaredManager
import com.shashank.hostterminal.runtime.EnvironmentBootstrapper
import com.shashank.hostterminal.ui.screens.AdminPanelScreen
import com.shashank.hostterminal.ui.screens.HomeScreen
import com.shashank.hostterminal.ui.screens.HostingDashboardScreen
import com.shashank.hostterminal.ui.screens.TelegramLoginScreen
import com.shashank.hostterminal.ui.screens.TerminalScreen
import com.shashank.hostterminal.ui.theme.AmbientBackdrop
import com.shashank.hostterminal.ui.theme.DeepSlateSurface
import com.shashank.hostterminal.ui.theme.ElectricCyan
import com.shashank.hostterminal.ui.theme.HostTerminalTheme
import com.shashank.hostterminal.ui.theme.TextMuted
import com.shashank.hostterminal.ui.theme.TextPrimary

object Routes {
    const val HOME = "home"
    const val TERMINAL = "terminal"
    const val HOSTING = "hosting"
    const val LOGIN = "login"
    const val ADMIN = "admin"
}

private data class NavTabItem(
    val route: String,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
)

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )

        // Immediately halt hosted processes & tunnels whenever Super Admin blocks a Chat ID
        AdminPolicyManager.getInstance(applicationContext).setOnUserBlockedListener { blockedChatId ->
            EnvironmentBootstrapper.getInstance(applicationContext).stopAllProjectsForUser(blockedChatId)
            if (ActiveServerState.session.value?.chatId == blockedChatId) {
                CloudflaredManager.getInstance(applicationContext).stopAllTunnels()
            }
        }

        setContent {
            HostTerminalTheme {
                HostTerminalApp()
            }
        }
    }
}

@Composable
private fun HostTerminalApp() {
    val navController = rememberNavController()
    val session by ActiveServerState.session.collectAsStateWithLifecycle()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route ?: Routes.HOME

    val hostingRoute = if (session != null) Routes.HOSTING else Routes.LOGIN

    val navItems = buildList {
        add(NavTabItem(Routes.HOME, "Home", Icons.Filled.Home, Icons.Outlined.Home))
        add(NavTabItem(Routes.TERMINAL, "Terminal", Icons.Filled.Terminal, Icons.Outlined.Terminal))
        add(NavTabItem(hostingRoute, "Hosting", Icons.Filled.CloudQueue, Icons.Outlined.CloudQueue))
        if (session?.isSuperAdmin == true) {
            add(
                NavTabItem(
                    Routes.ADMIN,
                    "Admin",
                    Icons.Filled.AdminPanelSettings,
                    Icons.Outlined.AdminPanelSettings
                )
            )
        }
    }

    fun navigateTopLevel(route: String) {
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    // Redirect back to login if active session is revoked/blocked while on Hosting or Admin screen
    LaunchedEffect(session) {
        if (session == null && (currentRoute == Routes.HOSTING || currentRoute == Routes.ADMIN)) {
            navController.navigate(Routes.LOGIN) {
                popUpTo(Routes.HOME) { inclusive = false }
                launchSingleTop = true
            }
        }
    }

    AmbientBackdrop {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val useNavigationRail = maxWidth >= 600.dp

            if (useNavigationRail) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.safeDrawing)
                ) {
                    NavigationRail(
                        containerColor = DeepSlateSurface.copy(alpha = 0.92f),
                        modifier = Modifier.fillMaxHeight()
                    ) {
                        navItems.forEach { item ->
                            val selected = currentRoute == item.route ||
                                (item.label == "Hosting" && (currentRoute == Routes.HOSTING || currentRoute == Routes.LOGIN))
                            NavigationRailItem(
                                selected = selected,
                                onClick = { navigateTopLevel(item.route) },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) item.selectedIcon else item.unselectedIcon,
                                        contentDescription = item.label
                                    )
                                },
                                label = { Text(item.label) },
                                colors = NavigationRailItemDefaults.colors(
                                    selectedIconColor = Color(0xFF04141D),
                                    selectedTextColor = ElectricCyan,
                                    indicatorColor = ElectricCyan,
                                    unselectedIconColor = TextMuted,
                                    unselectedTextColor = TextMuted
                                ),
                                modifier = Modifier.testTag("nav_rail_${item.label.lowercase()}")
                            )
                        }
                    }

                    Box(modifier = Modifier.weight(1f)) {
                        AppNavHost(
                            navController = navController,
                            onNavigateTopLevel = ::navigateTopLevel,
                            hostingRoute = hostingRoute
                        )
                    }
                }
            } else {
                Scaffold(
                    containerColor = Color.Transparent,
                    contentWindowInsets = WindowInsets.safeDrawing,
                    bottomBar = {
                        NavigationBar(
                            containerColor = DeepSlateSurface.copy(alpha = 0.95f),
                            tonalElevation = 8.dp
                        ) {
                            navItems.forEach { item ->
                                val selected = currentRoute == item.route ||
                                    (item.label == "Hosting" && (currentRoute == Routes.HOSTING || currentRoute == Routes.LOGIN))
                                NavigationBarItem(
                                    selected = selected,
                                    onClick = { navigateTopLevel(item.route) },
                                    icon = {
                                        Icon(
                                            imageVector = if (selected) item.selectedIcon else item.unselectedIcon,
                                            contentDescription = item.label
                                        )
                                    },
                                    label = { Text(item.label) },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = Color(0xFF04141D),
                                        selectedTextColor = TextPrimary,
                                        indicatorColor = ElectricCyan,
                                        unselectedIconColor = TextMuted,
                                        unselectedTextColor = TextMuted
                                    ),
                                    modifier = Modifier.testTag("nav_bar_${item.label.lowercase()}")
                                )
                            }
                        }
                    }
                ) { innerPadding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    ) {
                        AppNavHost(
                            navController = navController,
                            onNavigateTopLevel = ::navigateTopLevel,
                            hostingRoute = hostingRoute
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AppNavHost(
    navController: androidx.navigation.NavHostController,
    onNavigateTopLevel: (String) -> Unit,
    hostingRoute: String
) {
    NavHost(
        navController = navController,
        startDestination = Routes.HOME
    ) {
        composable(Routes.HOME) {
            HomeScreen(
                onOpenTerminal = { onNavigateTopLevel(Routes.TERMINAL) },
                onOpenHosting = { onNavigateTopLevel(hostingRoute) },
                onOpenAdmin = { onNavigateTopLevel(Routes.ADMIN) }
            )
        }
        composable(Routes.TERMINAL) {
            TerminalScreen()
        }
        composable(Routes.LOGIN) {
            TelegramLoginScreen(
                onAuthenticated = {
                    navController.navigate(Routes.HOSTING) {
                        popUpTo(Routes.LOGIN) { inclusive = true }
                        launchSingleTop = true
                    }
                }
            )
        }
        composable(Routes.HOSTING) {
            HostingDashboardScreen(
                onOpenTerminalForPlay = { onNavigateTopLevel(Routes.TERMINAL) },
                onLoggedOut = {
                    navController.navigate(Routes.LOGIN) {
                        popUpTo(Routes.HOSTING) { inclusive = true }
                        launchSingleTop = true
                    }
                }
            )
        }
        composable(Routes.ADMIN) {
            AdminPanelScreen()
        }
    }
}
