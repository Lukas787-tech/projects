package com.lukas.jarvis.data

import java.util.Locale

/**
 * Where in a shop a thing usually is, in the order most supermarkets lead
 * you round: fruit and veg by the door, bread, the fridges, meat and fish,
 * the long aisles, drinks, the freezers, then the things for the house.
 *
 * Words are matched in English and German, singular or plural, inside longer
 * names too ("free-range eggs", "Vollmilch"). Anything unknown goes last,
 * under "Everything else", rather than being guessed into an aisle.
 */
enum class Aisle(val label: String, private val words: List<String>) {
    Produce(
        "Fruit and veg",
        listOf(
            "apple", "banana", "orange", "lemon", "lime", "pear", "grape", "berry", "berries", "melon",
            "mango", "avocado", "tomato", "potato", "onion", "garlic", "carrot", "pepper", "cucumber",
            "lettuce", "salad", "spinach", "broccoli", "cauliflower", "courgette", "zucchini", "mushroom",
            "ginger", "herb", "basil", "parsley", "leek", "celery", "kiwi", "peach", "plum", "cherry",
            "apfel", "äpfel", "banane", "zitrone", "birne", "traube", "beere", "tomate", "kartoffel",
            "zwiebel", "knoblauch", "karotte", "möhre", "paprika", "gurke", "salat", "spinat", "pilz",
            "champignon", "obst", "gemüse", "kräuter", "lauch", "fruit", "veg"
        )
    ),
    Bakery("Bread", listOf("bread", "roll", "bagel", "croissant", "baguette", "toast", "bun", "brot", "brötchen", "semmel", "laugen", "breze", "brezel")),
    Dairy(
        "Dairy and eggs",
        listOf(
            "milk", "yoghurt", "yogurt", "cheese", "butter", "cream", "egg", "quark", "skyr", "kefir",
            "mozzarella", "feta", "parmesan", "cheddar", "milch", "joghurt", "käse", "sahne", "eier",
            "frischkäse", "schmand", "margarine", "oat milk", "hafermilch"
        )
    ),
    Meat(
        "Meat and fish",
        listOf(
            "chicken", "beef", "pork", "mince", "steak", "sausage", "bacon", "ham", "salami", "turkey",
            "fish", "salmon", "tuna", "prawn", "shrimp", "tofu", "hähnchen", "huhn", "rind", "schwein",
            "hack", "hackfleisch", "wurst", "würstchen", "schinken", "pute", "fisch", "lachs", "thunfisch", "garnele"
        )
    ),
    Cupboard(
        "Cupboard",
        listOf(
            "pasta", "spaghetti", "noodle", "rice", "flour", "sugar", "salt", "oil", "vinegar", "sauce",
            "ketchup", "mustard", "mayo", "honey", "jam", "peanut butter", "cereal", "muesli", "oats",
            "porridge", "beans", "lentil", "chickpea", "tin", "can of", "soup", "stock", "spice", "coffee",
            "tea", "cocoa", "nudel", "reis", "mehl", "zucker", "salz", "öl", "essig", "soße", "senf",
            "honig", "marmelade", "müsli", "haferflocken", "bohnen", "linsen", "kichererbsen", "dose",
            "suppe", "brühe", "gewürz", "kaffee", "tee", "tea bag", "olivenöl", "olive oil"
        )
    ),
    Snacks(
        "Sweets and snacks",
        listOf("chocolate", "crisps", "chips", "biscuit", "cookie", "sweets", "candy", "nuts", "popcorn", "schokolade", "keks", "kekse", "süßigkeiten", "nüsse", "gummibär")
    ),
    Drinks(
        "Drinks",
        listOf("water", "juice", "beer", "wine", "soda", "cola", "lemonade", "sprite", "wasser", "saft", "bier", "wein", "limo", "sprudel", "mate")
    ),
    Frozen("Freezer", listOf("frozen", "ice cream", "pizza", "fish fingers", "peas", "tiefkühl", "eis", "fischstäbchen", "erbsen")),
    Household(
        "Household",
        listOf(
            "toilet paper", "kitchen roll", "paper towel", "washing up", "detergent", "dishwasher", "bin bag",
            "sponge", "foil", "cling film", "batteries", "light bulb", "candle", "toilettenpapier", "klopapier",
            "küchenrolle", "spülmittel", "waschmittel", "müllbeutel", "schwamm", "alufolie", "batterie", "glühbirne", "kerze"
        )
    ),
    Care(
        "Bathroom and care",
        listOf(
            "shampoo", "soap", "toothpaste", "toothbrush", "deodorant", "deo", "razor", "plasters", "sun cream",
            "sunscreen", "tissues", "nappies", "diapers", "seife", "zahnpasta", "zahnbürste", "pflaster",
            "sonnencreme", "taschentücher", "windeln", "duschgel", "shower gel"
        )
    ),
    Other("Everything else", emptyList());

    companion object {
        /** Words that put anything in the freezer, whatever it is: "frozen spinach". */
        private val FROZEN = listOf("frozen", "tiefkühl", "tk ")

        /**
         * The aisle a thing is most likely in. The word that names the thing
         * wins, which in English and German alike is the last one: "orange
         * juice" is a drink, "tomato sauce" is in the cupboard, "Orangensaft"
         * is a drink. On a tie the longer word wins: "peanut butter" is not butter.
         */
        fun of(item: String): Aisle {
            val text = " " + item.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N} ]"), " ").replace(Regex("\\s+"), " ").trim() + " "
            if (FROZEN.any { text.contains(" $it") }) return Frozen
            var best: Aisle = Other
            var bestEnd = -1
            var bestLength = 0
            entries.forEach { aisle ->
                aisle.words.forEach { word ->
                    val end = end(text, word) ?: return@forEach
                    if (end > bestEnd || (end == bestEnd && word.length > bestLength)) {
                        best = aisle
                        bestEnd = end
                        bestLength = word.length
                    }
                }
            }
            return best
        }

        /**
         * Where in [text] the last word that [word] names ends, or null. A word
         * counts at the start of a word with at most two letters more ("eggs",
         * "tomatoes", not "eggplant" or "hamburger"), and a longer one also at
         * the end of a German compound ("Vollmilch", "Orangensaft"), which "eis"
         * is too short to be, so it does not turn up inside "Reis".
         */
        private fun end(text: String, word: String): Int? {
            var result: Int? = null
            var from = 0
            while (true) {
                val at = text.indexOf(" $word", from)
                if (at < 0) break
                val after = at + 1 + word.length
                val stop = text.indexOf(' ', after).let { if (it < 0) text.length else it }
                if (stop - after <= 2) result = maxOf(result ?: -1, stop)
                from = at + 1
            }
            if (word.length >= 4 && ' ' !in word) {
                Regex("\\p{L}+" + Regex.escape(word) + "\\p{L}{0,2}(?= )").findAll(text).lastOrNull()?.let {
                    result = maxOf(result ?: -1, it.range.last + 1)
                }
            }
            return result
        }

        /** The items grouped by aisle, aisles in walking order, items as they were added. */
        fun inShopOrder(items: List<ListItem>): List<Pair<Aisle, List<ListItem>>> =
            items.groupBy { of(it.text) }.entries.sortedBy { it.key.ordinal }.map { it.key to it.value }

        /**
         * What is still to get, one line per aisle in walking order, for a
         * notification or a spoken answer: "Fruit and veg: apples, bananas".
         * Any other list is one line, as it was made. Null when nothing is open.
         */
        fun walk(list: NamedList): String? {
            val open = list.open.ifEmpty { return null }
            if (!suits(list.name)) return open.joinToString(", ") { it.text }
            return inShopOrder(open).joinToString("\n") { (aisle, items) -> "${aisle.label}: ${items.joinToString(", ") { it.text }}" }
        }

        /** Whether [list] is the kind a shop order helps: the shopping list. */
        fun suits(list: String): Boolean = ListBook.canonical(list) == "shopping"
    }
}
