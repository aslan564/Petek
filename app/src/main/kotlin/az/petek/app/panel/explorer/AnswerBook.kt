/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.app.panel.explorer

import az.petek.app.panel.PanelTargets
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant

private val logger = KotlinLogging.logger {}

/**
 * The owner's answers to the explorer's questions, per site, kept in `<evidence>/explorer/answers.json` so they
 * survive a restart. Every later exploration of the same site is grounded by them: [grounding] appends them to the
 * owner's instructions, newest last, dropping the oldest ones when the text would not fit the explorer's limit.
 * Answering the same question again replaces the earlier answer.
 *
 * Thread-safe; [record] writes the file, so call it on `Dispatchers.IO`. A file that cannot be read starts an empty
 * book (logged); a failed write is logged and the answer is still used in this process.
 */
internal class AnswerBook(
    private val file: Path,
) {
    /** One answer; [site] is scheme, host and port ([PanelTargets.site]). */
    data class Answer(
        val site: String,
        val question: String,
        val answer: String,
        val explorationId: String,
        val unknownId: String,
        val at: Instant,
    )

    private val lock = Any()
    private val answers: MutableList<Answer> = load().toMutableList()

    fun answers(target: URI): List<Answer> {
        val site = PanelTargets.site(target)
        return synchronized(lock) { answers.filter { it.site == site } }
    }

    /** The answer given to [unknownId] of [explorationId], else to the same question on the same site. */
    fun answerFor(
        target: URI,
        explorationId: String,
        unknownId: String,
        question: String,
    ): String? {
        val own = answers(target)
        return own.lastOrNull { it.explorationId == explorationId && it.unknownId == unknownId }?.answer
            ?: own.lastOrNull { normalize(it.question) == normalize(question) }?.answer
    }

    fun record(
        target: URI,
        explorationId: String,
        unknownId: String,
        question: String,
        answer: String,
        at: Instant,
    ) {
        val entry = Answer(PanelTargets.site(target), question.trim(), answer.trim(), explorationId, unknownId, at)
        // Saved under the lock, so the file always ends with the newest book (blocking: call it on Dispatchers.IO).
        synchronized(lock) {
            answers.removeAll { it.site == entry.site && normalize(it.question) == normalize(entry.question) }
            answers += entry
            save(answers.toList())
        }
    }

    /**
     * [instructions] followed by the answers given for [target]'s site, at most [maxChars] characters: the owner's own
     * text always stays whole (the caller refuses it when it alone is too long), the oldest answers go first.
     */
    fun grounding(
        target: URI,
        instructions: String,
        maxChars: Int,
    ): String {
        val own = instructions.trim()
        val kept = answers(target).toMutableList()
        while (true) {
            val text = compose(own, kept)
            if (text.length <= maxChars || kept.isEmpty()) return text
            kept.removeAt(0)
        }
    }

    private fun compose(
        instructions: String,
        answers: List<Answer>,
    ): String {
        if (answers.isEmpty()) return instructions
        val block =
            answers.joinToString("\n", prefix = "$HEADING\n") { "- Sual: ${oneLine(it.question)}\n  Cavab: ${oneLine(it.answer)}" }
        return if (instructions.isEmpty()) block else "$instructions\n\n$block"
    }

    private fun oneLine(text: String): String = text.replace(Regex("\\s+"), " ").trim()

    private fun normalize(question: String): String = oneLine(question).lowercase()

    private fun load(): List<Answer> {
        if (!Files.isRegularFile(file)) return emptyList()
        return try {
            Json
                .parseToJsonElement(Files.readString(file))
                .jsonObject["answers"]
                .let { it as? JsonArray }
                .orEmpty()
                .mapNotNull { (it as? JsonObject)?.toAnswer() }
        } catch (e: Exception) {
            logger.warn(e) { "The explorer's answers in $file could not be read; starting without them" }
            emptyList()
        }
    }

    private fun save(snapshot: List<Answer>) {
        try {
            Files.createDirectories(file.parent)
            val json =
                buildJsonObject {
                    putJsonArray("answers") {
                        snapshot.forEach { answer ->
                            addJsonObject {
                                put("site", answer.site)
                                put("question", answer.question)
                                put("answer", answer.answer)
                                put("explorationId", answer.explorationId)
                                put("unknownId", answer.unknownId)
                                put("at", answer.at.toString())
                            }
                        }
                    }
                }
            val temporary = Files.createTempFile(file.parent, "answers-", ".json")
            Files.writeString(temporary, json.toString())
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: Exception) {
            logger.warn(e) { "The explorer's answers could not be saved to $file; they are used in this process only" }
        }
    }

    private fun JsonObject.toAnswer(): Answer? {
        fun text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        return Answer(
            site = text("site") ?: return null,
            question = text("question") ?: return null,
            answer = text("answer") ?: return null,
            explorationId = text("explorationId").orEmpty(),
            unknownId = text("unknownId").orEmpty(),
            at = text("at")?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: Instant.EPOCH,
        )
    }

    companion object {
        const val HEADING = "Kəşfiyyatçının suallarına cavablarım:"
        const val FILE_NAME = "answers.json"
    }
}
