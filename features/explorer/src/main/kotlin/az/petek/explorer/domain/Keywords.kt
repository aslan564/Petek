/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in
 * compliance with the License. You may obtain a copy of the License at https://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software distributed under the License is
 * distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 */

package az.petek.explorer.domain

import java.text.Normalizer
import java.util.Locale

/**
 * Word matching shared by the explorer's rules (link safety, form classification, instruction grounding).
 * Text is split into lower-case words of letters and digits (Azerbaijani letters included). Two words match when one
 * starts with the other and the shorter has at least [MIN_STEM] characters, so `ticket` matches `tickets` and `elan`
 * matches `elanlar` without a stemmer. Matching compares [fold]ed words, so `ÇIXIŞ`, `Çıxış` and `cixis` are one word
 * (upper-case Azerbaijani text lower-cases to dotted `i`, and URLs often carry the ASCII spelling).
 */
object Keywords {
    const val MIN_STEM = 3

    /** Combining marks belong to their word (`İ` lower-cases to `i` + U+0307), so they never split one. */
    private val SEPARATORS = Regex("[^\\p{L}\\p{M}\\p{N}]+")
    private val MARKS = Regex("\\p{M}+")

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

    /** [word] lower-cased without diacritics, `ı` as `i` and `ə` as `e`: `Çıxış` -> `cixis`, `ƏLAVƏ` -> `elave`. */
    fun fold(word: String): String {
        val lower = word.lowercase(Locale.ROOT).replace('ı', 'i').replace('ə', 'e')
        return MARKS.replace(Normalizer.normalize(lower, Normalizer.Form.NFD), "")
    }

    fun matches(
        a: String,
        b: String,
    ): Boolean {
        val first = fold(a)
        val second = fold(b)
        val shorter = if (first.length <= second.length) first else second
        val longer = if (first.length <= second.length) second else first
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
    ): Boolean {
        val folded = stems.map(::fold)
        return words(text).map(::fold).any { word -> folded.any { word == it || (it.length >= MIN_PREFIX_STEM && word.startsWith(it)) } }
    }

    /** True when the words of [phrase] occur in [text] one right after the other (`log in`, `daxil ol`), folded. */
    fun containsPhrase(
        text: String,
        phrase: String,
    ): Boolean {
        val wanted = words(phrase).map(::fold)
        if (wanted.isEmpty()) return false
        return words(text).map(::fold).windowed(wanted.size).any { it == wanted }
    }

    const val MIN_PREFIX_STEM = 5
}
