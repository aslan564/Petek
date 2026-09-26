/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.app.init

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path

/**
 * `petek init` (R10, R15): prepares a project directory so Pətək runs next to it and the project's AI coding agent
 * knows how to drive it. It writes
 *
 * - `.env` from the configuration template (never touched again once it exists: it holds secrets),
 * - `.petek/petek.yaml` (the project profile) and `.petek/SKILL.md` (the skill pack: roles and commands),
 * - for every host AI chosen or detected: a Pətək fragment in the agent's instruction file between
 *   `<!-- petek:begin -->` and `<!-- petek:end -->` (appended to the owner's text, replaced on a re-run, never
 *   overwriting anything else), a `petek` entry in the agent's project MCP configuration (merged into the JSON, other
 *   servers kept) and, for Claude Code, the skill under `.claude/skills/petek/`,
 * - `.env` and `evidence/` in `.gitignore`.
 *
 * Files Pətək owns (under `.petek/`, the skill copies) are created when absent and kept otherwise unless
 * [Request.force]; files the owner shares with
 * Pətək only ever gain or refresh the marked fragment or the one JSON entry. Every write is reported as a [Change].
 */
class ProjectInitializer(
    private val templates: InitTemplates = InitTemplates.bundled(),
) {
    /**
     * @property ais the agents to write for; null means detect them in the directory and fall back to [DEFAULT_AIS].
     * @property force rewrite the files Pətək owns (under `.petek/`, the skill copies) from the current templates; `.env` is
     *   never rewritten.
     */
    data class Request(
        val directory: Path,
        val target: URI? = null,
        val ais: Set<HostAi>? = null,
        val force: Boolean = false,
    )

    enum class Outcome { CREATED, UPDATED, KEPT, UNCHANGED }

    /** One file `init` looked at: [path] relative to the project, and what happened to it. */
    data class Change(
        val path: String,
        val outcome: Outcome,
        val note: String = "",
    )

    data class Result(
        val ais: Set<HostAi>,
        val detected: Boolean,
        val changes: List<Change>,
    ) {
        val written: List<Change> get() = changes.filter { it.outcome == Outcome.CREATED || it.outcome == Outcome.UPDATED }
    }

    fun initialize(request: Request): Result {
        val project = request.directory
        Files.createDirectories(project)
        val detected = request.ais == null
        val ais = request.ais ?: HostAi.detect(project).ifEmpty { DEFAULT_AIS }
        val changes = mutableListOf<Change>()

        changes += envFile(project, request.target)
        changes += owned(project, PROFILE, templates.profile(request.target), request.force)
        changes += owned(project, SKILL, templates.skill(), request.force)
        changes += gitignore(project)
        ais.sortedBy { it.ordinal }.forEach { ai ->
            if (ai == HostAi.CLAUDE) changes += owned(project, CLAUDE_SKILL, templates.skill(), request.force)
            changes += fragment(project, ai)
            ai.mcpFile?.let { changes += mcpEntry(project, it, ai.mcpServersKey) }
        }
        return Result(ais, detected, changes)
    }

    private fun envFile(
        project: Path,
        target: URI?,
    ): Change {
        val file = project.resolve(ENV)
        if (Files.exists(file)) return Change(ENV, Outcome.KEPT, "holds your configuration and secrets; not touched")
        Files.writeString(file, templates.env(target))
        return Change(ENV, Outcome.CREATED, if (target == null) "fill PETEK_TARGET" else "PETEK_TARGET=$target")
    }

    /** A file Pətək owns: written when absent, rewritten only with [force], reported as unchanged when equal. */
    private fun owned(
        project: Path,
        relative: String,
        content: String,
        force: Boolean,
    ): Change {
        val file = project.resolve(relative)
        if (!Files.exists(file)) {
            Files.createDirectories(file.parent)
            Files.writeString(file, content)
            return Change(relative, Outcome.CREATED)
        }
        if (Files.readString(file) == content) return Change(relative, Outcome.UNCHANGED)
        if (!force) return Change(relative, Outcome.KEPT, "differs from the current template; rewrite with --force")
        Files.writeString(file, content)
        return Change(relative, Outcome.UPDATED)
    }

    /** The Pətək fragment inside the agent's instruction file, between the markers; the owner's text stays as it is. */
    private fun fragment(
        project: Path,
        ai: HostAi,
    ): Change {
        val relative = ai.instructionFile
        val file = project.resolve(relative)
        val block = "$BEGIN\n${templates.fragment(ai)}\n$END\n"
        if (!Files.exists(file)) {
            Files.createDirectories(file.parent)
            Files.writeString(file, block)
            return Change(relative, Outcome.CREATED)
        }
        val current = Files.readString(file)
        val begin = current.indexOf(BEGIN)
        val end = current.indexOf(END, startIndex = maxOf(begin, 0))
        val next =
            if (begin >= 0 && end >= 0) {
                current.substring(0, begin) + block + current.substring(end + END.length).trimStart('\n')
            } else {
                current.trimEnd('\n') + "\n\n" + block
            }
        if (next == current) return Change(relative, Outcome.UNCHANGED)
        Files.writeString(file, next)
        return Change(relative, Outcome.UPDATED, if (begin >= 0) "fragment refreshed" else "fragment appended")
    }

    /** The `petek` server in the agent's project MCP file; other servers and keys are kept as they are. */
    private fun mcpEntry(
        project: Path,
        relative: String,
        serversKey: String,
    ): Change {
        val file = project.resolve(relative)
        val existed = Files.exists(file)
        val current: JsonObject =
            if (existed) {
                runCatching { json.parseToJsonElement(Files.readString(file)).jsonObject }
                    .getOrElse { return Change(relative, Outcome.KEPT, "is not a JSON object; add the petek server by hand") }
            } else {
                JsonObject(emptyMap())
            }
        val servers = current[serversKey]?.let { runCatching { it.jsonObject }.getOrNull() } ?: JsonObject(emptyMap())
        if (servers[MCP_SERVER] == PETEK_SERVER) return Change(relative, Outcome.UNCHANGED)
        val next = JsonObject(current + (serversKey to JsonObject(servers + (MCP_SERVER to PETEK_SERVER))))
        Files.createDirectories(file.parent)
        Files.writeString(file, json.encodeToString(JsonObject.serializer(), next) + "\n")
        return Change(relative, if (existed) Outcome.UPDATED else Outcome.CREATED, "server \"petek\"")
    }

    private fun gitignore(project: Path): Change {
        val file = project.resolve(GITIGNORE)
        val current = if (Files.exists(file)) Files.readString(file) else ""
        val lines = current.lines().map { it.trim() }.toSet()
        val missing = IGNORED.filter { it !in lines }
        if (missing.isEmpty()) return Change(GITIGNORE, Outcome.UNCHANGED)
        val next =
            (if (current.isEmpty()) "" else current.trimEnd('\n') + "\n\n") + "# Pətək: configuration with secrets, evidence of runs\n" +
                missing.joinToString("\n") +
                "\n"
        Files.writeString(file, next)
        return Change(GITIGNORE, if (current.isEmpty()) Outcome.CREATED else Outcome.UPDATED, missing.joinToString(", "))
    }

    companion object {
        const val ENV = ".env"
        const val PROFILE = ".petek/petek.yaml"
        const val SKILL = ".petek/SKILL.md"
        const val CLAUDE_SKILL = ".claude/skills/petek/SKILL.md"
        const val GITIGNORE = ".gitignore"
        const val BEGIN = "<!-- petek:begin -->"
        const val END = "<!-- petek:end -->"
        const val MCP_SERVER = "petek"

        /** When no agent is recognisable in the project: Claude Code and the cross-agent `AGENTS.md`. */
        val DEFAULT_AIS: Set<HostAi> = setOf(HostAi.CLAUDE, HostAi.CODEX)

        val IGNORED: List<String> = listOf(".env", "evidence/")

        /** `petek mcp` over stdio: the launcher on PATH (`npx petek` or a bundle's bin/). */
        val PETEK_SERVER: JsonObject =
            buildJsonObject {
                put("command", "petek")
                putJsonArray("args") { add(JsonPrimitive("mcp")) }
            }

        private val json = Json { prettyPrint = true }
    }
}
