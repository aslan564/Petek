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

package az.petek.identity.domain

import java.text.Normalizer
import kotlin.random.Random

/**
 * Chooses one unique display name per tester: the campaign's names first (in order), then catalog names.
 * A given single word is a first name and gets a catalog surname; a given "First Last" is used as is.
 * Every choice comes from `Random(seed)`, so the same inputs always produce the same people, independent of the run.
 * Display names are unique case-insensitively; catalog first names not already used are preferred, so the same
 * first name only repeats once the catalog is exhausted.
 *
 * Names never run out, whatever the number of testers. Candidates come in tiers ([Variant]): first name + surname;
 * then the same pairs with a patronymic for each of the catalog's fathers (`Əli Vüqar oğlu Məmmədov`); then the
 * plain pairs with an ordinal (`Əli Məmmədov II`, `III`, ...), written in Roman numerals so a name stays letters
 * only, as sign-up forms often require. Candidates are generated lazily and a taken one is skipped; only names
 * given by the campaign (and a patronymic after the person's own first name) are ever skipped, so the time grows
 * linearly with the number of testers, with no retries.
 */
internal class NameAllocator(
    private val catalog: NameCatalog,
) {
    private val firstNames = distinctNames(catalog.firstNames)
    private val surnames = distinctNames(catalog.surnames)
    private val fatherNames = distinctNames(catalog.fatherNames)

    val hasSurnames: Boolean get() = surnames.isNotEmpty()

    /** Whether the catalog can name testers the campaign gave no name for (it needs first names and surnames). */
    val canInventNames: Boolean get() = firstNames.isNotEmpty() && surnames.isNotEmpty()

    /** [given] must already be normalized and free of duplicates (see [IdentitySpecValidator]). */
    fun allocate(
        given: List<String>,
        total: Int,
        seed: Long,
    ): List<PersonName> {
        val random = Random(seed)
        val surnameOrder = surnames.shuffled(random)
        val firstNameOrder = firstNames.shuffled(random)
        // Drawn after the other two, so adding tiers left the names of smaller registries unchanged.
        val fatherOrder = fatherNames.shuffled(random)
        val taken = given.filter(::isFullName).mapTo(HashSet(), ::key)
        var singles = 0
        val chosen =
            given.map { name ->
                if (isFullName(name)) {
                    PersonName(firstName = name.substringBefore(' '), displayName = name)
                } else {
                    withCatalogSurname(name, surnameOrder, fatherOrder, singles++, taken)
                }
            }
        val catalogOrder = CatalogOrder(firstNameOrder, surnameOrder, fatherOrder, surnameOffset = singles)
        return chosen + fromCatalog(total - chosen.size, catalogOrder, chosen, taken)
    }

    /** The first free name for a given first name: its surnames in order, tier by tier. */
    private fun withCatalogSurname(
        firstName: String,
        surnameOrder: List<String>,
        fatherOrder: List<String>,
        offset: Int,
        taken: MutableSet<String>,
    ): PersonName {
        if (surnameOrder.isEmpty()) {
            throw IdentityConflictException("$CANNOT_BUILD the name catalog has no surnames for the given name '$firstName'")
        }
        for (variant in variants(fatherOrder)) {
            for (step in surnameOrder.indices) {
                val candidate = person(firstName, surnameOrder[(offset + step) % surnameOrder.size], variant) ?: continue
                if (taken.add(key(candidate.displayName))) return candidate
            }
        }
        error("the ordinal tier never ends")
    }

    /**
     * Walks all first-name/surname pairs, one round of first names per surname shift, skipping taken names, and
     * repeats the walk for every tier. Surnames start after the ones given first names took, so within a tier
     * surnames only repeat once the catalog wraps around.
     */
    private fun fromCatalog(
        count: Int,
        order: CatalogOrder,
        chosen: List<PersonName>,
        taken: MutableSet<String>,
    ): List<PersonName> {
        if (count <= 0) return emptyList()
        if (order.firstNames.isEmpty() || order.surnames.isEmpty()) {
            throw IdentityConflictException(
                "$CANNOT_BUILD the name catalog has no first names or no surnames, but $count more names are needed",
            )
        }
        val usedFirstNames = chosen.mapTo(HashSet()) { key(it.firstName) }
        val (fresh, reused) = order.firstNames.partition { key(it) !in usedFirstNames }
        val firstNames = fresh + reused
        val surnames = order.surnames
        val result = ArrayList<PersonName>(count)
        for (variant in variants(order.fathers)) {
            for (shift in surnames.indices) {
                for ((i, firstName) in firstNames.withIndex()) {
                    val candidate = person(firstName, surnames[(order.surnameOffset + i + shift) % surnames.size], variant)
                    if (candidate != null && taken.add(key(candidate.displayName))) {
                        result += candidate
                        if (result.size == count) return result
                    }
                }
            }
        }
        error("the ordinal tier never ends")
    }

    /** The tiers in the order they are used: plain, one per father, then ordinals II, III, ... without end. */
    private fun variants(fathers: List<String>): Sequence<Variant> =
        sequenceOf(Variant.Plain) +
            fathers.asSequence().map(Variant::Patronymic) +
            generateSequence(FIRST_ORDINAL) { it + 1 }.map(Variant::Ordinal)

    /** The display name of [firstName] + [surname] in [variant]; null for a patronymic after the person's own name. */
    private fun person(
        firstName: String,
        surname: String,
        variant: Variant,
    ): PersonName? {
        val family = catalog.surnameFor(firstName, surname)
        val displayName =
            when (variant) {
                Variant.Plain -> {
                    "$firstName $family"
                }

                is Variant.Patronymic -> {
                    if (key(variant.father) == key(firstName)) return null
                    "$firstName ${catalog.patronymic(firstName, variant.father)} $family"
                }

                is Variant.Ordinal -> {
                    "$firstName $family ${roman(variant.number)}"
                }
            }
        return PersonName(firstName = firstName, displayName = displayName)
    }

    /** How a first name and a surname are combined in one tier of candidates. */
    private sealed interface Variant {
        data object Plain : Variant

        data class Patronymic(
            val father: String,
        ) : Variant

        data class Ordinal(
            val number: Int,
        ) : Variant
    }

    /** The seeded order in which catalog names are tried. */
    private class CatalogOrder(
        val firstNames: List<String>,
        val surnames: List<String>,
        val fathers: List<String>,
        val surnameOffset: Int,
    )

    /** A tester's name as shown on screen ([displayName]) and the part the e-mail is built from ([firstName]). */
    data class PersonName(
        val firstName: String,
        val displayName: String,
    )

    companion object {
        /** Common prefix of every registry conflict message. */
        const val CANNOT_BUILD = "Identity registry cannot be built:"

        /** The first ordinal written after a name: the second person of that name is `II`. */
        private const val FIRST_ORDINAL = 2

        private val WHITESPACE = Regex("\\s+")

        private val ROMAN =
            listOf(
                1000 to "M",
                900 to "CM",
                500 to "D",
                400 to "CD",
                100 to "C",
                90 to "XC",
                50 to "L",
                40 to "XL",
                10 to "X",
                9 to "IX",
                5 to "V",
                4 to "IV",
                1 to "I",
            )

        /** Trims and collapses inner whitespace, so `" Əli   Kərimov "` and `"Əli Kərimov"` are the same name. */
        fun normalize(raw: String): String =
            raw
                .trim()
                .split(WHITESPACE)
                .filter { it.isNotEmpty() }
                .joinToString(" ")

        /**
         * Comparison key: names that differ only in letter case are the same person, also for the Azerbaijani
         * alphabet. Its two i's (`i`/`İ` and `ı`/`I`) do not pair up like English ones, so a plain [lowercase]
         * keeps `SATIŞ` and `Satış` (or `ƏLİ` and `Əli`) apart; all four fold to `i` here, which keeps `IT` = `it`
         * too. Composed and decomposed spellings of a letter (`ü` and `u` + `◌̈`) are unified first.
         */
        fun key(name: String): String =
            buildString(name.length) {
                Normalizer.normalize(name, Normalizer.Form.NFC).forEach { append(if (it in I_LETTERS) 'i' else it) }
            }.lowercase()

        /** `I`, `İ` and `ı`; the fourth, `i`, is the target of the fold. */
        private const val I_LETTERS = "Iİı"

        fun isFullName(normalized: String): Boolean = ' ' in normalized

        /** [number] (at least 1) in Roman numerals; thousands beyond 3999 simply repeat `M`. */
        fun roman(number: Int): String {
            require(number >= 1) { "Roman numerals start at 1, was $number" }
            var rest = number
            return buildString {
                for ((value, letters) in ROMAN) {
                    while (rest >= value) {
                        append(letters)
                        rest -= value
                    }
                }
            }
        }

        private fun distinctNames(names: List<String>): List<String> = names.map(::normalize).filter { it.isNotEmpty() }.distinctBy(::key)
    }
}
