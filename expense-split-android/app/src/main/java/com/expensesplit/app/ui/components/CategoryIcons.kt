package com.expensesplit.app.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocalHospital
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Spa
import androidx.compose.ui.graphics.vector.ImageVector

/** Maps a category's stored icon key onto a Material icon. Unknown keys degrade to a question mark. */
object CategoryIcons {

    private val byKey: Map<String, ImageVector> = mapOf(
        "cart" to Icons.Filled.ShoppingCart,
        "restaurant" to Icons.Filled.Restaurant,
        "car" to Icons.Filled.DirectionsCar,
        "home" to Icons.Filled.Home,
        "bolt" to Icons.Filled.Bolt,
        "health" to Icons.Filled.LocalHospital,
        "movie" to Icons.Filled.Movie,
        "bag" to Icons.Filled.ShoppingBag,
        "school" to Icons.Filled.School,
        "flight" to Icons.Filled.Flight,
        "repeat" to Icons.Filled.Repeat,
        "spa" to Icons.Filled.Spa,
        "gift" to Icons.Filled.CardGiftcard,
        "sparkle" to Icons.Filled.AutoAwesome,
        "help" to Icons.Filled.HelpOutline,
    )

    /** The choices offered when creating a custom category. */
    val selectableKeys: List<String> = byKey.keys.toList()

    operator fun get(key: String?): ImageVector = byKey[key] ?: Icons.Filled.HelpOutline
}
