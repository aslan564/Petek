package az.petek.identity.domain

import kotlin.random.Random

/**
 * Chooses one unique display name per tester: the campaign's names first (in order), then catalog names.
 * A given single word is a first name and gets a catalog surname; a given "First Last" is used as is.
 * Every choice comes from `Random(seed)`, so the same inputs always produce the same people, independent of the run.
 * Display names are unique case-insensitively; catalog first names not already used are preferred, so the same
 * first name only repeats once the catalog is exhausted.
 */
internal class NameAllocator(
    private val catalog: NameCatalog,
) {
    private val firstNames =
        catalog.firstNames
            .map(::normalize)
            .filter { it.isNotEmpty() }
            .distinct()
    private val surnames =
        catalog.surnames
            .map(::normalize)
            .filter { it.isNotEmpty() }
            .distinct()

    val hasSurnames: Boolean get() = surnames.isNotEmpty()

    /** How many distinct first-name/surname pairs the catalog can produce. */
    val capacity: Long get() = firstNames.size.toLong() * surnames.size

    /** [given] must already be normalized and free of duplicates (see [IdentitySpecValidator]). */
    fun allocate(
        given: List<String>,
        total: Int,
        seed: Long,
    ): List<PersonName> {
        val random = Random(seed)
        val surnameOrder = surnames.shuffled(random)
        val firstNameOrder = firstNames.shuffled(random)
        val taken = given.filter(::isFullName).mapTo(HashSet(), ::key)
        var singles = 0
        val chosen =
            given.map { name ->
                if (isFullName(name)) {
                    PersonName(firstName = name.substringBefore(' '), displayName = name)
                } else {
                    withCatalogSurname(name, surnameOrder, singles++, taken)
                }
            }
        val catalogOrder = CatalogOrder(firstNameOrder, surnameOrder, surnameOffset = singles)
        return chosen + fromCatalog(total - chosen.size, catalogOrder, chosen, taken)
    }

    private fun withCatalogSurname(
        firstName: String,
        surnameOrder: List<String>,
        offset: Int,
        taken: MutableSet<String>,
    ): PersonName {
        for (step in surnameOrder.indices) {
            val candidate = person(firstName, surnameOrder[(offset + step) % surnameOrder.size])
            if (taken.add(key(candidate.displayName))) return candidate
        }
        throw IdentityConflictException(
            "$CANNOT_BUILD the name catalog has no unused surname left for the given name '$firstName'",
        )
    }

    /**
     * Walks all first-name/surname pairs, one round of first names per surname shift, skipping taken names.
     * Surnames start after the ones given first names took, so surnames only repeat once the catalog wraps around.
     */
    private fun fromCatalog(
        count: Int,
        order: CatalogOrder,
        chosen: List<PersonName>,
        taken: MutableSet<String>,
    ): List<PersonName> {
        if (count <= 0) return emptyList()
        val usedFirstNames = chosen.mapTo(HashSet()) { key(it.firstName) }
        val (fresh, reused) = order.firstNames.partition { key(it) !in usedFirstNames }
        val firstNames = fresh + reused
        val surnames = order.surnames
        val result = ArrayList<PersonName>(count)
        for (shift in surnames.indices) {
            for ((i, firstName) in firstNames.withIndex()) {
                val candidate = person(firstName, surnames[(order.surnameOffset + i + shift) % surnames.size])
                if (taken.add(key(candidate.displayName))) {
                    result += candidate
                    if (result.size == count) return result
                }
            }
        }
        throw IdentityConflictException(
            "$CANNOT_BUILD the name catalog is too small: $count more unique names are needed but only " +
                "${result.size} are left",
        )
    }

    private fun person(
        firstName: String,
        surname: String,
    ) = PersonName(firstName = firstName, displayName = "$firstName ${catalog.surnameFor(firstName, surname)}")

    /** The seeded order in which catalog names are tried. */
    private class CatalogOrder(
        val firstNames: List<String>,
        val surnames: List<String>,
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

        private val WHITESPACE = Regex("\\s+")

        /** Trims and collapses inner whitespace, so `" Əli   Kərimov "` and `"Əli Kərimov"` are the same name. */
        fun normalize(raw: String): String =
            raw
                .trim()
                .split(WHITESPACE)
                .filter { it.isNotEmpty() }
                .joinToString(" ")

        /** Comparison key: names that differ only in letter case are the same person. */
        fun key(name: String): String = name.lowercase()

        fun isFullName(normalized: String): Boolean = ' ' in normalized
    }
}
