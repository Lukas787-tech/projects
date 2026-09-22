package com.lukas.jarvis.core

import java.util.Locale

/**
 * Offline unit conversion.
 *
 * Everything here is a fixed ratio to one base unit per family, so a conversion
 * is one multiply and one divide and never needs the network. Temperature is the
 * exception — it has an offset as well as a scale — so it is handled apart from
 * the ratio table rather than bent into it.
 */
object Units {

    private data class Measure(val family: String, val ratio: Double, val canonical: String)

    sealed interface Result {
        data class Ok(val value: Double, val unit: String, val spoken: String) : Result
        data class Error(val reason: String) : Result
    }

    fun convert(amount: Double, fromRaw: String, toRaw: String): Result {
        val from = key(fromRaw)
        val to = key(toRaw)
        if (from.isBlank() || to.isBlank()) return Result.Error("I need both units.")

        if (from in TEMPERATURES && to in TEMPERATURES) {
            val celsius = toCelsius(amount, from)
            val out = fromCelsius(celsius, to)
            return Result.Ok(out, label(to), "${Calculator.format(amount)} ${label(from)} is " +
                "${Calculator.format(out)} ${label(to)}")
        }

        val source = TABLE[from] ?: return Result.Error("I do not know the unit '$fromRaw'.")
        val target = TABLE[to] ?: return Result.Error("I do not know the unit '$toRaw'.")
        if (source.family != target.family) {
            return Result.Error(
                "${source.canonical} measures ${source.family} and ${target.canonical} measures " +
                    "${target.family}, so there is nothing to convert between them."
            )
        }

        val value = amount * source.ratio / target.ratio
        return Result.Ok(
            value = value,
            unit = target.canonical,
            spoken = "${Calculator.format(amount)} ${source.canonical} is " +
                "${Calculator.format(value)} ${target.canonical}"
        )
    }

    /** Every unit name the tool will accept, for the schema's enum-free prose. */
    fun known(): String = FAMILIES.entries.joinToString("; ") { (family, names) ->
        "$family: ${names.joinToString(", ")}"
    }

    private fun key(raw: String): String =
        raw.trim().lowercase(Locale.ROOT).removeSuffix(".").let { ALIASES[it] ?: it }

    private fun label(temperature: String): String = when (temperature) {
        "c" -> "°C"
        "f" -> "°F"
        else -> "K"
    }

    private fun toCelsius(value: Double, unit: String): Double = when (unit) {
        "f" -> (value - 32.0) * 5.0 / 9.0
        "k" -> value - 273.15
        else -> value
    }

    private fun fromCelsius(celsius: Double, unit: String): Double = when (unit) {
        "f" -> celsius * 9.0 / 5.0 + 32.0
        "k" -> celsius + 273.15
        else -> celsius
    }

    private val TEMPERATURES = setOf("c", "f", "k")

    private val TABLE: Map<String, Measure> = buildMap {
        fun family(name: String, vararg units: Triple<String, Double, String>) {
            units.forEach { (key, ratio, canonical) -> put(key, Measure(name, ratio, canonical)) }
        }
        // Base: metre, gram, litre, second, byte, metre per second, joule.
        family(
            "length",
            Triple("mm", 0.001, "millimetres"),
            Triple("cm", 0.01, "centimetres"),
            Triple("m", 1.0, "metres"),
            Triple("km", 1000.0, "kilometres"),
            Triple("in", 0.0254, "inches"),
            Triple("ft", 0.3048, "feet"),
            Triple("yd", 0.9144, "yards"),
            Triple("mi", 1609.344, "miles"),
            Triple("nmi", 1852.0, "nautical miles")
        )
        family(
            "mass",
            Triple("mg", 0.001, "milligrams"),
            Triple("g", 1.0, "grams"),
            Triple("kg", 1000.0, "kilograms"),
            Triple("t", 1_000_000.0, "tonnes"),
            Triple("oz", 28.349523125, "ounces"),
            Triple("lb", 453.59237, "pounds"),
            Triple("st", 6350.29318, "stone")
        )
        family(
            "volume",
            Triple("ml", 0.001, "millilitres"),
            Triple("cl", 0.01, "centilitres"),
            Triple("l", 1.0, "litres"),
            Triple("tsp", 0.00492892159375, "teaspoons"),
            Triple("tbsp", 0.01478676478125, "tablespoons"),
            Triple("cup", 0.2365882365, "cups"),
            Triple("pt", 0.473176473, "pints"),
            Triple("qt", 0.946352946, "quarts"),
            Triple("gal", 3.785411784, "gallons")
        )
        family(
            "time",
            Triple("ms", 0.001, "milliseconds"),
            Triple("s", 1.0, "seconds"),
            Triple("min", 60.0, "minutes"),
            Triple("h", 3600.0, "hours"),
            Triple("d", 86400.0, "days"),
            Triple("wk", 604800.0, "weeks"),
            Triple("yr", 31_557_600.0, "years")
        )
        family(
            "data",
            Triple("b", 1.0, "bytes"),
            Triple("kb", 1024.0, "kilobytes"),
            Triple("mb", 1_048_576.0, "megabytes"),
            Triple("gb", 1_073_741_824.0, "gigabytes"),
            Triple("tb", 1_099_511_627_776.0, "terabytes")
        )
        family(
            "speed",
            Triple("mps", 1.0, "metres per second"),
            Triple("kph", 0.2777777778, "kilometres per hour"),
            Triple("mph", 0.44704, "miles per hour"),
            Triple("kn", 0.5144444444, "knots")
        )
        family(
            "energy",
            Triple("j", 1.0, "joules"),
            Triple("kj", 1000.0, "kilojoules"),
            Triple("cal", 4.184, "calories"),
            Triple("kcal", 4184.0, "kilocalories"),
            Triple("wh", 3600.0, "watt hours"),
            Triple("kwh", 3_600_000.0, "kilowatt hours")
        )
    }

    /** What people say, mapped onto what the table is keyed by. */
    private val ALIASES: Map<String, String> = mapOf(
        "millimetre" to "mm", "millimetres" to "mm", "millimeter" to "mm", "millimeters" to "mm",
        "centimetre" to "cm", "centimetres" to "cm", "centimeter" to "cm", "centimeters" to "cm",
        "metre" to "m", "metres" to "m", "meter" to "m", "meters" to "m",
        "kilometre" to "km", "kilometres" to "km", "kilometer" to "km", "kilometers" to "km",
        "inch" to "in", "inches" to "in",
        "foot" to "ft", "feet" to "ft",
        "yard" to "yd", "yards" to "yd",
        "mile" to "mi", "miles" to "mi",
        "milligram" to "mg", "milligrams" to "mg",
        "gram" to "g", "grams" to "g",
        "kilogram" to "kg", "kilograms" to "kg", "kilo" to "kg", "kilos" to "kg",
        "tonne" to "t", "tonnes" to "t", "ton" to "t", "tons" to "t",
        "ounce" to "oz", "ounces" to "oz",
        "pound" to "lb", "pounds" to "lb", "lbs" to "lb",
        "millilitre" to "ml", "millilitres" to "ml", "milliliter" to "ml", "milliliters" to "ml",
        "litre" to "l", "litres" to "l", "liter" to "l", "liters" to "l",
        "teaspoon" to "tsp", "teaspoons" to "tsp",
        "tablespoon" to "tbsp", "tablespoons" to "tbsp",
        "cups" to "cup",
        "pint" to "pt", "pints" to "pt",
        "quart" to "qt", "quarts" to "qt",
        "gallon" to "gal", "gallons" to "gal",
        "second" to "s", "seconds" to "s", "sec" to "s", "secs" to "s",
        "minute" to "min", "minutes" to "min", "mins" to "min",
        "hour" to "h", "hours" to "h", "hr" to "h", "hrs" to "h",
        "day" to "d", "days" to "d",
        "week" to "wk", "weeks" to "wk",
        "year" to "yr", "years" to "yr",
        "byte" to "b", "bytes" to "b",
        "kilobyte" to "kb", "kilobytes" to "kb",
        "megabyte" to "mb", "megabytes" to "mb",
        "gigabyte" to "gb", "gigabytes" to "gb", "gig" to "gb", "gigs" to "gb",
        "terabyte" to "tb", "terabytes" to "tb",
        "km/h" to "kph", "kmh" to "kph", "kilometres per hour" to "kph",
        "m/s" to "mps", "metres per second" to "mps",
        "miles per hour" to "mph",
        "knot" to "kn", "knots" to "kn",
        "joule" to "j", "joules" to "j",
        "kilojoule" to "kj", "kilojoules" to "kj",
        "calorie" to "cal", "calories" to "cal",
        "kilocalorie" to "kcal", "kilocalories" to "kcal",
        "celsius" to "c", "centigrade" to "c", "°c" to "c", "degrees celsius" to "c",
        "fahrenheit" to "f", "°f" to "f", "degrees fahrenheit" to "f",
        "kelvin" to "k"
    )

    private val FAMILIES = mapOf(
        "length" to listOf("mm", "cm", "m", "km", "in", "ft", "yd", "mi"),
        "mass" to listOf("g", "kg", "t", "oz", "lb", "st"),
        "volume" to listOf("ml", "l", "tsp", "tbsp", "cup", "pt", "gal"),
        "time" to listOf("s", "min", "h", "d", "wk", "yr"),
        "data" to listOf("b", "kb", "mb", "gb", "tb"),
        "speed" to listOf("kph", "mph", "m/s", "kn"),
        "energy" to listOf("j", "kj", "cal", "kcal", "kwh"),
        "temperature" to listOf("c", "f", "k")
    )
}
