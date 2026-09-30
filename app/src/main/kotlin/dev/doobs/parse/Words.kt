package dev.doobs.parse

/**
 * Text tidying shared by the command and moment parsers.
 *
 * Speech recognisers are inconsistent about numbers — the same utterance can come
 * back as "seven thirty", "7:30" or "7 30" — so everything is folded to digits
 * before any pattern matching happens.
 */
internal object Words {

    private val ONES = mapOf(
        "zero" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4,
        "five" to 5, "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9,
        "ten" to 10, "eleven" to 11, "twelve" to 12, "thirteen" to 13,
        "fourteen" to 14, "fifteen" to 15, "sixteen" to 16, "seventeen" to 17,
        "eighteen" to 18, "nineteen" to 19,
    )

    private val TENS = mapOf(
        "twenty" to 20, "thirty" to 30, "forty" to 40, "fourty" to 40, "fifty" to 50,
    )

    private val WHITESPACE = Regex("\\s+")
    private val JUNK = Regex("[^a-z0-9:' ]")

    /** Lowercase, de-punctuate, and collapse whitespace. Apostrophes survive, for labels. */
    fun normalize(raw: String): String = raw
        .lowercase()
        .replace('’', '\'')
        .replace("a.m.", "am")
        .replace("p.m.", "pm")
        .replace("o'clock", " ")
        .replace(JUNK, " ")
        .replace("oclock", " ")
        .replace(WHITESPACE, " ")
        .trim()

    /**
     * Turn spoken numbers into digits: "seven thirty" -> "7 30",
     * "twenty five" -> "25", "seven oh five" -> "7 05".
     */
    fun digitize(text: String): String {
        val tokens = text.split(' ').filter { it.isNotEmpty() }
        val out = ArrayList<String>(tokens.size)
        var i = 0
        while (i < tokens.size) {
            val token = tokens[i]
            val next = tokens.getOrNull(i + 1)

            // "oh five" / "o five" is a zero-padded minute, not the number zero.
            if (token == "oh" || token == "o") {
                val digit = next?.let { ONES[it] }
                if (digit != null && digit <= 9) {
                    out += "0$digit"
                    i += 2
                    continue
                }
            }

            val tens = TENS[token]
            if (tens != null) {
                val unit = next?.let { ONES[it] }
                if (unit != null && unit in 1..9) {
                    out += (tens + unit).toString()
                    i += 2
                } else {
                    out += tens.toString()
                    i++
                }
                continue
            }

            val one = ONES[token]
            out += one?.toString() ?: token
            i++
        }
        return out.joinToString(" ")
    }
}
