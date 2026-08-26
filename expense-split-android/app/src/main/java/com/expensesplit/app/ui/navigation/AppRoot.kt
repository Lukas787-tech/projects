package com.expensesplit.app.ui.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.expensesplit.app.R

/**
 * The app shell: bottom navigation, the quick-add FAB and a single snackbar host shared by every
 * screen. The FAB and the bar hide on full-screen flows (the camera scanner, the add form) so those
 * screens get the whole viewport.
 */
@Composable
fun AppRoot(startDeepLink: String? = null) {
    val navController = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    LaunchedEffect(startDeepLink) {
        routeForDeepLink(startDeepLink)?.let { route ->
            navController.navigate(route) { launchSingleTop = true }
        }
    }

    val showChrome = currentRoute == null || currentRoute !in FULL_SCREEN_ROUTES

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            AnimatedVisibility(
                visible = showChrome,
                enter = slideInVertically { it },
                exit = slideOutVertically { it },
            ) {
                AppBottomBar(navController = navController, currentRoute = currentRoute)
            }
        },
        floatingActionButton = {
            AnimatedVisibility(
                visible = showChrome && currentRoute in FAB_ROUTES,
                enter = slideInVertically { it * 2 },
                exit = slideOutVertically { it * 2 },
            ) {
                val label = stringResource(R.string.action_add_expense)
                ExtendedFloatingActionButton(
                    onClick = { navController.navigateToTab(Routes.ADD_EXPENSE) },
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text(label) },
                    modifier = Modifier.semantics { contentDescription = label },
                )
            }
        },
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            AppNavHost(
                navController = navController,
                snackbarHostState = snackbarHostState,
            )
        }
    }
}

@Composable
private fun AppBottomBar(navController: NavHostController, currentRoute: String?) {
    NavigationBar {
        TopLevelDestination.entries.forEach { destination ->
            val selected = currentRoute?.startsWith(destination.route) == true ||
                navController.currentBackStackEntry?.destination?.hierarchy
                    ?.any { it.route == destination.route } == true

            NavigationBarItem(
                selected = selected,
                onClick = { navController.navigateToTab(destination.route) },
                icon = {
                    Icon(
                        imageVector = if (selected) destination.selectedIcon else destination.unselectedIcon,
                        contentDescription = null,
                    )
                },
                label = { Text(stringResource(destination.labelRes)) },
                alwaysShowLabel = true,
            )
        }
    }
}

/**
 * Standard bottom-nav behaviour: one entry per tab on the back stack, state preserved when
 * switching between them, and back from any tab returns to the start destination.
 */
private fun NavHostController.navigateToTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/** Routes that take over the whole screen, hiding the bar and FAB. */
private val FULL_SCREEN_ROUTES = setOf(
    Routes.SCANNER,
    Routes.ADD_EXPENSE,
    Routes.EDIT_EXPENSE,
)

/** Routes where the quick-add FAB makes sense. */
private val FAB_ROUTES = setOf(
    Routes.DASHBOARD,
    Routes.EXPENSE_LIST,
    Routes.ANALYTICS,
)
