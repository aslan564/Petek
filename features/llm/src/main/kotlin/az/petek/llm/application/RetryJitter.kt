package az.petek.llm.application

/**
 * Spreads the retries of concurrent agents apart so they do not hit a recovering provider at the same instant.
 * Returns a fraction in `[0, 1)`. It is a pure function of its inputs (no `Random`), so a run with the same labels
 * waits exactly the same way every time and tests can assert the delays.
 */
fun interface RetryJitter {
    fun fraction(
        label: String,
        attempt: Int,
    ): Double

    companion object {
        /** No jitter: plain exponential backoff. */
        val NONE: RetryJitter = RetryJitter { _, _ -> 0.0 }

        /**
         * Default: derived from the request label (whose prefix is the agent id) and the attempt number, so two agents
         * retrying the same step get different delays while one agent's delays stay reproducible.
         */
        val DETERMINISTIC: RetryJitter =
            RetryJitter { label, attempt ->
                val mixed = mix(label.hashCode().toLong() * PRIME + attempt)
                (mixed ushr EXPONENT_BITS).toDouble() / MANTISSA_RANGE
            }

        private const val PRIME = 1_000_003L
        private const val EXPONENT_BITS = 11
        private const val MANTISSA_RANGE = (1L shl 53).toDouble()

        /** SplitMix64 finalizer: turns nearby inputs (attempt 1, 2, 3) into unrelated outputs. */
        private fun mix(seed: Long): Long {
            var z = seed + -0x61c8864680b583ebL
            z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
            z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
            return z xor (z ushr 31)
        }
    }
}
