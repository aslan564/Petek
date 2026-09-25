package az.petek.evidence.infrastructure

import az.petek.core.ids.ArtifactId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.util.Locale

/*
 * Column encodings shared by every evidence table. They are chosen so that the SQLite file stays readable with
 * plain SQL tools and survives enum additions: no CHECK constraints, no binary blobs.
 */

/** Ids are opaque strings; the limit only documents intent (SQLite does not enforce VARCHAR lengths). */
internal const val ID_LENGTH = 128

/** Longest enum constant name we expect, with generous headroom. */
internal const val ENUM_LENGTH = 32

/**
 * Instants are stored as ISO-8601 UTC text with exactly nine fractional digits, e.g.
 * `2026-01-01T10:00:00.120000000Z`. The fixed width makes text order equal time order (for years 0000–9999),
 * so `ORDER BY` works on the column, and nanoseconds survive the round trip unchanged.
 */
internal object InstantText {
    const val LENGTH = 30

    private val format: DateTimeFormatter = DateTimeFormatterBuilder().appendInstant(9).toFormatter(Locale.ROOT)

    fun encode(instant: Instant): String = format.format(instant)

    fun decode(text: String): Instant = Instant.parse(text)
}

/** A JSON array of artifact id strings, e.g. `["art_1","art_2"]`. */
internal object ArtifactIdsJson {
    fun encode(ids: List<ArtifactId>): String = JsonArray(ids.map { JsonPrimitive(it.value) }).toString()

    fun decode(text: String): List<ArtifactId> = Json.parseToJsonElement(text).jsonArray.map { ArtifactId(it.jsonPrimitive.content) }
}

internal fun Table.instant(name: String): Column<Instant> =
    varchar(name, InstantText.LENGTH).transform(InstantText::decode, InstantText::encode)

/** Stores an enum by its [Enum.name], so reordering constants never corrupts existing rows. */
internal inline fun <reified E : Enum<E>> Table.enumName(name: String): Column<E> =
    varchar(name, ENUM_LENGTH).transform({ enumValueOf<E>(it) }, { it.name })

internal fun Table.artifactIds(name: String): Column<List<ArtifactId>> =
    text(name).transform(ArtifactIdsJson::decode, ArtifactIdsJson::encode)
