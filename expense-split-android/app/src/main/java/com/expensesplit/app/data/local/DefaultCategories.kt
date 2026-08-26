package com.expensesplit.app.data.local

import com.expensesplit.app.data.local.entity.CategoryEntity

/**
 * Seeded on first launch. `nameResName` holds the *name* of a string resource rather than its
 * integer id, because resource ids are not stable across builds while the database is.
 *
 * The keyword lists drive [com.expensesplit.app.domain.ocr.AutoCategorizer]; they are matched
 * case-insensitively against the merchant name, the expense title and OCR line items.
 */
object DefaultCategories {

    const val UNCATEGORIZED_KEY = "uncategorized"

    val seed: List<CategoryEntity> = listOf(
        CategoryEntity(
            id = 1,
            key = UNCATEGORIZED_KEY,
            nameResName = "category_uncategorized",
            customName = null,
            colorArgb = 0xFF78909C,
            iconKey = "help",
            keywords = emptyList(),
            sortOrder = 100,
        ),
        CategoryEntity(
            id = 2,
            key = "groceries",
            nameResName = "category_groceries",
            customName = null,
            colorArgb = 0xFF43A047,
            iconKey = "cart",
            keywords = listOf(
                "supermarket", "grocery", "groceries", "market", "aldi", "lidl", "tesco", "kroger",
                "walmart", "carrefour", "mercadona", "rewe", "edeka", "sainsbury", "costco", "safeway",
                "whole foods", "trader joe", "migros", "coop", "auchan", "leclerc",
            ),
            sortOrder = 1,
        ),
        CategoryEntity(
            id = 3,
            key = "dining",
            nameResName = "category_dining",
            customName = null,
            colorArgb = 0xFFFB8C00,
            iconKey = "restaurant",
            keywords = listOf(
                "restaurant", "cafe", "coffee", "bar", "pub", "bistro", "pizza", "sushi", "burger",
                "starbucks", "mcdonald", "kfc", "subway", "dominos", "diner", "grill", "bakery",
                "doordash", "ubereats", "deliveroo", "just eat", "takeaway", "brasserie",
            ),
            sortOrder = 2,
        ),
        CategoryEntity(
            id = 4,
            key = "transport",
            nameResName = "category_transport",
            customName = null,
            colorArgb = 0xFF1E88E5,
            iconKey = "car",
            keywords = listOf(
                "uber", "lyft", "taxi", "metro", "subway rail", "bus", "train", "railway", "sncf",
                "renfe", "deutsche bahn", "transit", "parking", "toll", "fuel", "gas station",
                "petrol", "shell", "bp", "esso", "total", "chevron", "flight", "airline", "airport",
            ),
            sortOrder = 3,
        ),
        CategoryEntity(
            id = 5,
            key = "housing",
            nameResName = "category_housing",
            customName = null,
            colorArgb = 0xFF8E24AA,
            iconKey = "home",
            keywords = listOf(
                "rent", "mortgage", "landlord", "hoa", "property", "lease", "maintenance", "repair",
                "furniture", "ikea", "hardware", "home depot", "lowes",
            ),
            sortOrder = 4,
        ),
        CategoryEntity(
            id = 6,
            key = "utilities",
            nameResName = "category_utilities",
            customName = null,
            colorArgb = 0xFF00897B,
            iconKey = "bolt",
            keywords = listOf(
                "electric", "electricity", "power", "water", "gas bill", "internet", "broadband",
                "wifi", "mobile", "phone bill", "telecom", "vodafone", "orange", "at&t", "verizon",
                "comcast", "heating", "waste", "sewer",
            ),
            sortOrder = 5,
        ),
        CategoryEntity(
            id = 7,
            key = "health",
            nameResName = "category_health",
            customName = null,
            colorArgb = 0xFFE53935,
            iconKey = "health",
            keywords = listOf(
                "pharmacy", "chemist", "doctor", "clinic", "hospital", "dentist", "optician",
                "medical", "health", "insurance", "therapy", "boots", "cvs", "walgreens", "apotheke",
            ),
            sortOrder = 6,
        ),
        CategoryEntity(
            id = 8,
            key = "entertainment",
            nameResName = "category_entertainment",
            customName = null,
            colorArgb = 0xFFD81B60,
            iconKey = "movie",
            keywords = listOf(
                "cinema", "movie", "theatre", "theater", "concert", "netflix", "spotify", "disney",
                "hbo", "prime video", "playstation", "xbox", "steam", "game", "museum", "festival",
                "ticket", "bowling", "club",
            ),
            sortOrder = 7,
        ),
        CategoryEntity(
            id = 9,
            key = "shopping",
            nameResName = "category_shopping",
            customName = null,
            colorArgb = 0xFF3949AB,
            iconKey = "bag",
            keywords = listOf(
                "amazon", "ebay", "zara", "h&m", "uniqlo", "nike", "adidas", "clothing", "apparel",
                "shoes", "electronics", "apple store", "best buy", "mediamarkt", "fnac", "asos",
                "shein", "boutique",
            ),
            sortOrder = 8,
        ),
        CategoryEntity(
            id = 10,
            key = "education",
            nameResName = "category_education",
            customName = null,
            colorArgb = 0xFF6D4C41,
            iconKey = "school",
            keywords = listOf(
                "school", "university", "college", "tuition", "course", "udemy", "coursera", "books",
                "bookstore", "stationery", "library", "exam", "training",
            ),
            sortOrder = 9,
        ),
        CategoryEntity(
            id = 11,
            key = "travel",
            nameResName = "category_travel",
            customName = null,
            colorArgb = 0xFF00ACC1,
            iconKey = "flight",
            keywords = listOf(
                "hotel", "hostel", "airbnb", "booking.com", "expedia", "resort", "tour", "travel",
                "luggage", "visa fee", "car rental", "hertz", "avis",
            ),
            sortOrder = 10,
        ),
        CategoryEntity(
            id = 12,
            key = "subscriptions",
            nameResName = "category_subscriptions",
            customName = null,
            colorArgb = 0xFF7CB342,
            iconKey = "repeat",
            keywords = listOf(
                "subscription", "membership", "monthly plan", "annual plan", "icloud", "dropbox",
                "google one", "microsoft 365", "adobe", "gym", "fitness",
            ),
            sortOrder = 11,
        ),
        CategoryEntity(
            id = 13,
            key = "personal_care",
            nameResName = "category_personal_care",
            customName = null,
            colorArgb = 0xFFAB47BC,
            iconKey = "spa",
            keywords = listOf(
                "salon", "barber", "haircut", "spa", "cosmetics", "sephora", "beauty", "nails",
                "massage", "skincare",
            ),
            sortOrder = 12,
        ),
        CategoryEntity(
            id = 14,
            key = "gifts",
            nameResName = "category_gifts",
            customName = null,
            colorArgb = 0xFFF4511E,
            iconKey = "gift",
            keywords = listOf("gift", "present", "donation", "charity", "florist", "flowers"),
            sortOrder = 13,
        ),
    )
}
