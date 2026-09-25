package az.petek.identity.domain

import az.petek.core.ids.AgentId
import az.petek.core.ids.RunTag
import az.petek.core.security.Secret
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Derives each tester's password as HMAC-SHA256(secret, "<run tag>:<agent id>"), so a run's passwords can be
 * re-derived at any time (login after a crash, teardown) without storing the secret next to them, and nobody can
 * guess them from the run tag alone.
 *
 * The digest is mapped to [LENGTH] characters that satisfy common password policies: an upper-case first letter,
 * then at least one lower-case letter, one digit and exactly one symbol from [SYMBOLS], in an order also taken from
 * the digest. Look-alike characters (`I O l 0 1`) are left out so a person can retype a password from the database.
 * The mapping is part of the contract: changing it changes every stored identity's password.
 *
 * [secret] must not be empty; the app derives a fallback secret itself when none is configured. It is copied, so
 * later changes to the caller's array have no effect.
 */
class HmacPasswordDeriver(
    secret: ByteArray,
) : PasswordDeriver {
    private val key: SecretKeySpec

    init {
        require(secret.isNotEmpty()) { "Password secret must not be empty" }
        key = SecretKeySpec(secret.copyOf(), ALGORITHM)
    }

    override fun derive(
        runTag: RunTag,
        agentId: AgentId,
    ): Secret {
        val mac = Mac.getInstance(ALGORITHM).apply { init(key) }
        val digest = mac.doFinal("${runTag.value}:${agentId.value}".toByteArray(Charsets.UTF_8))
        return Secret(shape(DigestReader(digest)))
    }

    private fun shape(digest: DigestReader): String {
        val first = digest.pick(UPPER)
        val rest = mutableListOf(digest.pick(LOWER), digest.pick(DIGITS), digest.pick(SYMBOLS))
        repeat(LENGTH - 1 - rest.size) { rest += digest.pick(ALPHANUMERIC) }
        for (i in rest.lastIndex downTo 1) {
            val j = digest.next() % (i + 1)
            rest[i] = rest[j].also { rest[j] = rest[i] }
        }
        return first + rest.joinToString("")
    }

    /** Hands out the digest's bytes one by one as unsigned values; 30 of the 32 bytes are used. */
    private class DigestReader(
        private val bytes: ByteArray,
    ) {
        private var position = 0

        fun next(): Int = bytes[position++].toInt() and 0xFF

        fun pick(alphabet: String): Char = alphabet[next() % alphabet.length]
    }

    companion object {
        const val LENGTH = 16

        /**
         * The only non-alphanumeric characters a password contains (exactly one of them). A subset of the allowed
         * `!@#%*-_`: `_` is a word character (sign-up forms checking `(?=.*\W)` would not count it) and `-`/`_` are
         * missing from the widespread `[!@#$%^&*]` rule, so either could make a target reject the password.
         */
        const val SYMBOLS = "!@#%*"

        private const val ALGORITHM = "HmacSHA256"
        private const val UPPER = "ABCDEFGHJKLMNPQRSTUVWXYZ"
        private const val LOWER = "abcdefghijkmnopqrstuvwxyz"
        private const val DIGITS = "23456789"
        private const val ALPHANUMERIC = UPPER + LOWER + DIGITS
    }
}
