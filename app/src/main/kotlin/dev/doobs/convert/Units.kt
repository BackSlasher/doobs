package dev.doobs.convert

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/**
 * Unit conversion, worked out on the phone.
 *
 * Deliberately a fixed table rather than a search: the answer appears on the
 * sheet instantly, offline, and the same way every time. The cost is that a
 * unit not listed here is simply not understood — which is the honest failure,
 * and better than silently guessing.
 *
 * US customary where it differs from imperial (pints, gallons, fluid ounces),
 * because that is what "pint" means in most software.
 */
object Units {

    enum class Family { LENGTH, MASS, TEMPERATURE, VOLUME, SPEED }

    /** [perBase] is how many base units one of these is worth; ignored for temperature. */
    data class Unit(
        val family: Family,
        val symbol: String,
        val perBase: Double,
        val names: List<String>,
    )

    private val TABLE: List<Unit> = buildList {
        // Length, base metre
        length("mm", 0.001, "mm", "millimetre", "millimetres", "millimeter", "millimeters")
        length("cm", 0.01, "cm", "centimetre", "centimetres", "centimeter", "centimeters")
        length("m", 1.0, "m", "metre", "metres", "meter", "meters")
        length("km", 1000.0, "km", "kilometre", "kilometres", "kilometer", "kilometers")
        length("in", 0.0254, "in", "inch", "inches")
        length("ft", 0.3048, "ft", "foot", "feet")
        length("yd", 0.9144, "yd", "yard", "yards")
        length("mi", 1609.344, "mi", "mile", "miles")

        // Mass, base kilogram
        mass("mg", 0.000001, "mg", "milligram", "milligrams")
        mass("g", 0.001, "g", "gram", "grams")
        mass("kg", 1.0, "kg", "kilogram", "kilograms", "kilo", "kilos")
        mass("t", 1000.0, "t", "tonne", "tonnes", "metric ton", "metric tons")
        mass("oz", 0.028349523125, "oz", "ounce", "ounces")
        mass("lb", 0.45359237, "lb", "lbs", "pound", "pounds")
        mass("st", 6.35029318, "st", "stone", "stones")

        // Temperature — converted by formula, not by ratio.
        temperature("°C", "c", "celsius", "centigrade", "degrees celsius")
        temperature("°F", "f", "fahrenheit", "degrees fahrenheit")
        temperature("K", "k", "kelvin", "kelvins")

        // Volume, base litre
        volume("ml", 0.001, "ml", "millilitre", "millilitres", "milliliter", "milliliters")
        volume("l", 1.0, "l", "litre", "litres", "liter", "liters")
        volume("tsp", 0.00492892159375, "tsp", "teaspoon", "teaspoons")
        volume("tbsp", 0.01478676478125, "tbsp", "tablespoon", "tablespoons")
        volume("fl oz", 0.0295735295625, "fl oz", "fluid ounce", "fluid ounces")
        volume("cup", 0.2365882365, "cup", "cups")
        volume("pt", 0.473176473, "pt", "pint", "pints")
        volume("qt", 0.946352946, "qt", "quart", "quarts")
        volume("gal", 3.785411784, "gal", "gallon", "gallons")

        // Speed, base metre per second
        speed("km/h", 0.2777777778, "kph", "km/h", "kmh", "kilometres per hour", "kilometers per hour")
        speed("mph", 0.44704, "mph", "miles per hour")
        speed("m/s", 1.0, "m/s", "metres per second", "meters per second")
        speed("kn", 0.5144444444, "kn", "knot", "knots")
    }

    private fun MutableList<Unit>.length(symbol: String, perBase: Double, vararg names: String) =
        add(Unit(Family.LENGTH, symbol, perBase, names.toList()))

    private fun MutableList<Unit>.mass(symbol: String, perBase: Double, vararg names: String) =
        add(Unit(Family.MASS, symbol, perBase, names.toList()))

    private fun MutableList<Unit>.volume(symbol: String, perBase: Double, vararg names: String) =
        add(Unit(Family.VOLUME, symbol, perBase, names.toList()))

    private fun MutableList<Unit>.speed(symbol: String, perBase: Double, vararg names: String) =
        add(Unit(Family.SPEED, symbol, perBase, names.toList()))

    private fun MutableList<Unit>.temperature(symbol: String, vararg names: String) =
        add(Unit(Family.TEMPERATURE, symbol, 1.0, names.toList()))

    private val BY_NAME: Map<String, Unit> =
        TABLE.flatMap { unit -> unit.names.map { it to unit } }.toMap()

    /** Looks up a spoken unit. "degrees" alone is meaningless, so it is not a name. */
    fun find(spoken: String): Unit? {
        val cleaned = spoken.trim().lowercase()
            .removePrefix("degrees ").removePrefix("degree ")
            .trim()
        BY_NAME[cleaned]?.let { return it }
        // "centimeter" vs "centimeters" is covered above, but speech invents plurals.
        return BY_NAME[cleaned.removeSuffix("s")]
    }

    /** Null when the units are of different kinds — grams into inches has no answer. */
    fun convert(amount: Double, from: Unit, to: Unit): Double? {
        if (from.family != to.family) return null
        if (from.family == Family.TEMPERATURE) {
            return fromCelsius(toCelsius(amount, from), to)
        }
        return amount * from.perBase / to.perBase
    }

    private fun toCelsius(value: Double, unit: Unit) = when (unit.symbol) {
        "°F" -> (value - 32.0) * 5.0 / 9.0
        "K" -> value - 273.15
        else -> value
    }

    private fun fromCelsius(celsius: Double, unit: Unit) = when (unit.symbol) {
        "°F" -> celsius * 9.0 / 5.0 + 32.0
        "K" -> celsius + 273.15
        else -> celsius
    }

    /**
     * Four significant figures, trailing zeros dropped: "4.331", "100", "0.3937".
     * Enough to act on, short enough to read off a sheet at a glance.
     */
    fun format(value: Double): String {
        if (!value.isFinite()) return "?"
        val rounded = BigDecimal(value).round(MathContext(4, RoundingMode.HALF_UP))
        return rounded.stripTrailingZeros().toPlainString()
    }
}
