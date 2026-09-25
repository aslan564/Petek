package az.petek.explorer.domain

import java.util.Locale

/**
 * Word matching shared by the explorer's rules (link safety, form classification, instruction grounding).
 * Text is split into lower-case words of letters and digits (Azerbaijani letters included). Two words match when one
 * starts with the other and the shorter has at least [MIN_STEM] characters, so `ticket` matches `tickets` and `elan`
 * matches `elanlar` without a stemmer.
 */
object Keywords {
    const val MIN_STEM = 3

    private val SEPARATORS = Regex("[^\\p{L}\\p{N}]+")

    /** Words that carry no meaning for grounding, in English and Azerbaijani. */
    private val STOP_WORDS =
        setOf(
            "the",
            "and",
            "for",
            "with",
            "from",
            "this",
            "that",
            "into",
            "onto",
            "about",
            "all",
            "any",
            "are",
            "was",
            "please",
            "test",
            "tests",
            "testing",
            "page",
            "pages",
            "site",
            "check",
            "make",
            "sure",
            "should",
            "must",
            "və",
            "ilə",
            "üçün",
            "bu",
            "bir",
            "da",
            "də",
            "ki",
            "olan",
            "olsun",
            "səhifə",
            "səhifəsi",
            "yoxla",
        )

    /** Every word of [text], lower-cased, in order (duplicates kept). */
    fun words(text: String): List<String> = text.lowercase(Locale.ROOT).split(SEPARATORS).filter { it.isNotEmpty() }

    /** The meaningful words of owner instructions: at least [MIN_STEM] characters, no stop words, distinct. */
    fun of(text: String?): Set<String> =
        if (text == null) emptySet() else words(text).filter { it.length >= MIN_STEM && it !in STOP_WORDS }.toCollection(LinkedHashSet())

    fun matches(
        a: String,
        b: String,
    ): Boolean {
        val shorter = if (a.length <= b.length) a else b
        val longer = if (a.length <= b.length) b else a
        return shorter.length >= MIN_STEM && longer.startsWith(shorter)
    }

    /** How many of [keywords] occur in [text] (each keyword counts once). */
    fun score(
        keywords: Set<String>,
        text: String,
    ): Int {
        if (keywords.isEmpty()) return 0
        val candidates = words(text)
        return keywords.count { keyword -> candidates.any { matches(keyword, it) } }
    }

    /**
     * True when a word of [text] is one of [stems], or starts with a stem of at least [MIN_PREFIX_STEM] characters.
     * Short stems must match whole words, so `sil` (delete) does not match `silver` and `drop` not `dropdown`.
     */
    fun containsStem(
        text: String,
        stems: Collection<String>,
    ): Boolean = words(text).any { word -> stems.any { word == it || (it.length >= MIN_PREFIX_STEM && word.startsWith(it)) } }

    const val MIN_PREFIX_STEM = 5
}
