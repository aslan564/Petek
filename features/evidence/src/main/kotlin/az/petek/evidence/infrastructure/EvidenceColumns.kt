/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

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
 * plain SQL tools and survives enum additions: no CHECK constraints on enum columns, no binary blobs.
 *
 * Every string is a `TEXT` column, never `VARCHAR(n)`: SQLite would not enforce the length, but Exposed does, on
 * the client, before the statement runs. A bounded column would therefore turn a long id, scenario step or new
 * enum name into an exception at record time, and that evidence would be lost.
 */

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

internal fun Table.instant(name: String): Column<Instant> = text(name).transform(InstantText::decode, InstantText::encode)

/** Stores an enum by its [Enum.name], so reordering constants never corrupts existing rows. */
internal inline fun <reified E : Enum<E>> Table.enumName(name: String): Column<E> =
    text(name).transform({ enumValueOf<E>(it) }, { it.name })

internal fun Table.artifactIds(name: String): Column<List<ArtifactId>> =
    text(name).transform(ArtifactIdsJson::decode, ArtifactIdsJson::encode)
