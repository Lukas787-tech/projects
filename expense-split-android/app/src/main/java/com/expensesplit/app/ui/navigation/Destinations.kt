package com.expensesplit.app.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.ui.graphics.vector.ImageVector
import com.expensesplit.app.R
import com.expensesplit.app.notifications.DeepLinks

/** Every route in the app. Argument names are kept next to the route that declares them. */
object Routes {
    const val DASHBOARD = "dashboard"
    const val ADD_EXPENSE = "add_expense"
    const val BILLS = "bills"
    const val ANALYTICS = "analytics"
    const val SETTINGS = "settings"

    const val EXPENSE_LIST = "expenses"
    const val SEARCH = "search"
    const val RECAP = "recap"
    const val SCANNER = "scanner"
    const val RECEIPT_GALLERY = "receipts"
    const val BUDGETS = "budgets"
    const val RECURRING = "recurring"

    const val ARG_EXPENSE_ID = "expenseId"
    const val ARG_RECEIPT_ID = "receiptId"
    const val ARG_GROUP_ID = "groupId"
    const val ARG_ITEM_NAME = "itemName"

    /** Passing 0 opens the form empty; any other id edits that expense. */
    const val EDIT_EXPENSE = "add_expense?$ARG_EXPENSE_ID={$ARG_EXPENSE_ID}"
    const val RECEIPT_DETAIL = "receipt/{$ARG_RECEIPT_ID}"
    const val GROUP_DETAIL = "group/{$ARG_GROUP_ID}"
    const val PRICE_HISTORY = "price_history/{$ARG_ITEM_NAME}"

    fun editExpense(expenseId: Long): String = "add_expense?$ARG_EXPENSE_ID=$expenseId"
    fun receiptDetail(receiptId: Long): String = "receipt/$receiptId"
    fun groupDetail(groupId: Long): String = "group/$groupId"
    fun priceHistory(itemName: String): String =
        "price_history/${android.net.Uri.encode(itemName)}"
}

/** The five bottom-navigation destinations, in tab order. */
enum class TopLevelDestination(
    val route: String,
    val labelRes: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
) {
    DASHBOARD(
        Routes.DASHBOARD,
        R.string.nav_dashboard,
        Icons.Filled.Home,
        Icons.Outlined.Home,
    ),
    ADD(
        Routes.ADD_EXPENSE,
        R.string.nav_add,
        Icons.Filled.AddCircle,
        Icons.Outlined.AddCircleOutline,
    ),
    BILLS(
        Routes.BILLS,
        R.string.nav_bills,
        Icons.Filled.Groups,
        Icons.Outlined.Groups,
    ),
    ANALYTICS(
        Routes.ANALYTICS,
        R.string.nav_analytics,
        Icons.Filled.BarChart,
        Icons.Outlined.BarChart,
    ),
    SETTINGS(
        Routes.SETTINGS,
        R.string.nav_settings,
        Icons.Filled.Settings,
        Icons.Outlined.Settings,
    ),
}

/** Maps a notification deep link onto the route it should open. */
fun routeForDeepLink(deepLink: String?): String? = when (deepLink) {
    DeepLinks.DASHBOARD -> Routes.DASHBOARD
    DeepLinks.EXPENSES -> Routes.EXPENSE_LIST
    DeepLinks.BILLS -> Routes.BILLS
    DeepLinks.ANALYTICS -> Routes.ANALYTICS
    DeepLinks.RECAP -> Routes.RECAP
    DeepLinks.RECEIPTS -> Routes.RECEIPT_GALLERY
    else -> null
}
