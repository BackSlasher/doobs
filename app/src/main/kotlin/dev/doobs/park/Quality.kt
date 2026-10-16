package dev.doobs.park

/**
 * How much a fix is worth for finding a car again.
 *
 * The numbers come from what the figure physically means. A parking space is
 * about 2.4 × 4.8 m and you can pick your own car out of an open lot from
 * roughly 25 m, so:
 *
 *  - under 10 m you are standing at the car;
 *  - under 25 m you are in the right row and will see it;
 *  - beyond that you are hunting, and a wrong answer confidently given is
 *    worse than no answer, so it is not saved at all.
 *
 * Worth remembering that Android reports accuracy as a 68% confidence radius,
 * not a bound — real error can be about double at 95% — so every one of these
 * is already the optimistic reading.
 *
 * Kept free of Android types so the thresholds can be tested on the JVM.
 */
enum class Quality {
    EXCELLENT,
    ACCEPTABLE,
    TOO_ROUGH,
    ;

    val usable: Boolean get() = this != TOO_ROUGH

    companion object {
        const val EXCELLENT_METRES = 10f
        const val ACCEPTABLE_METRES = 25f

        /**
         * [accuracyMetres] null means the fix carries no accuracy at all, which
         * cannot be certified as good — treated as too rough rather than
         * assumed fine.
         */
        fun of(accuracyMetres: Float?): Quality = when {
            accuracyMetres == null || accuracyMetres <= 0f -> TOO_ROUGH
            accuracyMetres < EXCELLENT_METRES -> EXCELLENT
            accuracyMetres <= ACCEPTABLE_METRES -> ACCEPTABLE
            else -> TOO_ROUGH
        }
    }
}
