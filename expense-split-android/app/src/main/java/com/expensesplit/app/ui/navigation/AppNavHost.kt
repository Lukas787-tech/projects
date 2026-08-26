package com.expensesplit.app.ui.navigation

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.expensesplit.app.ui.screens.addexpense.AddExpenseScreen
import com.expensesplit.app.ui.screens.analytics.AnalyticsScreen
import com.expensesplit.app.ui.screens.bills.BillsScreen
import com.expensesplit.app.ui.screens.bills.GroupDetailScreen
import com.expensesplit.app.ui.screens.dashboard.DashboardScreen
import com.expensesplit.app.ui.screens.expenses.ExpenseListScreen
import com.expensesplit.app.ui.screens.recap.RecapScreen
import com.expensesplit.app.ui.screens.receipt.PriceHistoryScreen
import com.expensesplit.app.ui.screens.receipt.ReceiptDetailScreen
import com.expensesplit.app.ui.screens.receipt.ReceiptGalleryScreen
import com.expensesplit.app.ui.screens.recurring.RecurringScreen
import com.expensesplit.app.ui.screens.scanner.ReceiptScannerScreen
import com.expensesplit.app.ui.screens.search.SearchScreen
import com.expensesplit.app.ui.screens.settings.SettingsScreen

/**
 * Every route in one graph.
 *
 * Ids travel as string arguments rather than typed longs, because Navigation's optional arguments
 * only support defaults reliably for strings; each ViewModel parses its own id out of
 * [androidx.lifecycle.SavedStateHandle].
 */
@Composable
fun AppNavHost(
    navController: NavHostController,
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    // Carries the receipt id from the scanner back into the add-expense form.
    var scannedReceiptId by rememberSaveable { mutableLongStateOf(0L) }

    NavHost(
        navController = navController,
        startDestination = Routes.DASHBOARD,
        modifier = modifier,
        enterTransition = { slideInHorizontally(tween(240)) { it / 6 } + fadeIn(tween(240)) },
        exitTransition = { fadeOut(tween(160)) },
        popEnterTransition = { fadeIn(tween(200)) },
        popExitTransition = { slideOutHorizontally(tween(240)) { it / 6 } + fadeOut(tween(200)) },
    ) {
        composable(Routes.DASHBOARD) {
            DashboardScreen(
                onExpenseClick = { navController.navigate(Routes.editExpense(it)) },
                onSeeAllExpenses = { navController.navigate(Routes.EXPENSE_LIST) },
                onOpenAnalytics = { navController.navigate(Routes.ANALYTICS) },
                onOpenBills = { navController.navigate(Routes.BILLS) },
                onOpenScanner = { navController.navigate(Routes.SCANNER) },
                onAddExpense = { navController.navigate(Routes.ADD_EXPENSE) },
            )
        }

        composable(
            route = Routes.EDIT_EXPENSE,
            arguments = listOf(
                navArgument(Routes.ARG_EXPENSE_ID) {
                    type = NavType.StringType
                    defaultValue = "0"
                },
            ),
        ) {
            AddExpenseScreen(
                onDone = {
                    scannedReceiptId = 0L
                    navController.popBackStack()
                },
                onOpenScanner = { navController.navigate(Routes.SCANNER) },
                scannedReceiptId = scannedReceiptId.takeIf { it != 0L },
            )
        }

        composable(Routes.SCANNER) {
            ReceiptScannerScreen(
                onCancel = { navController.popBackStack() },
                onScanned = { receiptId ->
                    scannedReceiptId = receiptId
                    // Return to the add form if it is already on the stack, otherwise open it.
                    val returned = navController.popBackStack(Routes.EDIT_EXPENSE, false)
                    if (!returned) {
                        navController.navigate(Routes.ADD_EXPENSE) {
                            popUpTo(Routes.SCANNER) { inclusive = true }
                        }
                    }
                },
            )
        }

        composable(Routes.EXPENSE_LIST) {
            ExpenseListScreen(
                onExpenseClick = { navController.navigate(Routes.editExpense(it)) },
                onBack = { navController.popBackStack() },
                onOpenSearch = { navController.navigate(Routes.SEARCH) },
            )
        }

        composable(Routes.SEARCH) {
            SearchScreen(
                onExpenseClick = { navController.navigate(Routes.editExpense(it)) },
                onReceiptClick = { navController.navigate(Routes.receiptDetail(it)) },
                onItemClick = { navController.navigate(Routes.priceHistory(it)) },
                onBack = { navController.popBackStack() },
            )
        }

        composable(Routes.BILLS) {
            BillsScreen(onGroupClick = { navController.navigate(Routes.groupDetail(it)) })
        }

        composable(
            route = Routes.GROUP_DETAIL,
            arguments = listOf(navArgument(Routes.ARG_GROUP_ID) { type = NavType.StringType }),
        ) {
            GroupDetailScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.ANALYTICS) {
            AnalyticsScreen(onOpenRecap = { navController.navigate(Routes.RECAP) })
        }

        composable(Routes.RECAP) {
            RecapScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(
                onOpenRecurring = { navController.navigate(Routes.RECURRING) },
                onOpenReceiptGallery = { navController.navigate(Routes.RECEIPT_GALLERY) },
                snackbarHostState = snackbarHostState,
            )
        }

        composable(Routes.RECURRING) {
            RecurringScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.RECEIPT_GALLERY) {
            ReceiptGalleryScreen(
                onReceiptClick = { navController.navigate(Routes.receiptDetail(it)) },
                onBack = { navController.popBackStack() },
            )
        }

        composable(
            route = Routes.RECEIPT_DETAIL,
            arguments = listOf(navArgument(Routes.ARG_RECEIPT_ID) { type = NavType.StringType }),
        ) {
            ReceiptDetailScreen(
                onBack = { navController.popBackStack() },
                onItemClick = { navController.navigate(Routes.priceHistory(it)) },
            )
        }

        composable(
            route = Routes.PRICE_HISTORY,
            arguments = listOf(navArgument(Routes.ARG_ITEM_NAME) { type = NavType.StringType }),
        ) {
            PriceHistoryScreen(onBack = { navController.popBackStack() })
        }
    }
}
