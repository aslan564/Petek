/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.explorer.application

import az.petek.browser.domain.BrowserActionException
import az.petek.browser.domain.BrowserSession
import az.petek.browser.domain.PageSnapshot
import az.petek.core.ids.ArtifactId
import az.petek.core.ids.IdGenerator
import az.petek.core.ids.StepId
import az.petek.core.time.HarnessClock
import az.petek.evidence.domain.ArtifactStore
import az.petek.evidence.domain.ArtifactType
import az.petek.explorer.domain.ExplorationArtifacts
import az.petek.explorer.domain.ExplorationId
import az.petek.explorer.domain.HtmlScanner
import az.petek.explorer.domain.ScannedDocument
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import java.net.URI

private val logger = KotlinLogging.logger {}

/** A loaded page as evidence: what the explorer reads from it and the artifacts that prove it. */
internal data class CapturedPage(
    val snapshot: PageSnapshot,
    val document: ScannedDocument,
    val screenshot: ArtifactId?,
    val dom: ArtifactId?,
) {
    val evidence: List<ArtifactId> get() = listOfNotNull(screenshot, dom)
}

/**
 * Loads pages and turns them into evidence under the exploration's own evidence directory
 * (`<evidence root>/exp_…/<viewpoint>/…`); every artifact record is stored with [records] so the panel can resolve an
 * artifact id to its file. Load time is measured by the harness clock (CLAUDE.md rule 1). The snapshot is taken
 * before the DOM, so the DOM carries the snapshot's element refs and both can be joined. A failing screenshot or DOM
 * capture costs that artifact only, never the page.
 */
internal class PageCapture(
    private val artifacts: ArtifactStore,
    private val records: ExplorationArtifacts,
    private val clock: HarnessClock,
    private val ids: IdGenerator,
    private val explorationId: ExplorationId,
) {
    /** Navigates and returns the load time in milliseconds. */
    suspend fun load(
        session: BrowserSession,
        url: URI,
    ): Long {
        val start = clock.now()
        session.navigate(url.toString())
        return start.elapsedUntil(clock.now()).inWholeMilliseconds
    }

    suspend fun capture(
        session: BrowserSession,
        owner: String,
    ): CapturedPage {
        val snapshot = session.snapshot()
        val html = optional("DOM snapshot") { session.domSnapshot() }
        val step = ids.stepId()
        val screenshot = optional("screenshot") { session.screenshot() }?.let { write(step, owner, ArtifactType.SCREENSHOT, it) }
        val dom = html?.let { write(step, owner, ArtifactType.DOM, it.toByteArray()) }
        return CapturedPage(snapshot, html?.let(HtmlScanner::scan) ?: ScannedDocument.EMPTY, screenshot, dom)
    }

    /** Stores an HTTP answer as evidence (`<status> <body>`, body cut to [MAX_BODY_CHARS]). */
    suspend fun httpEvidence(
        owner: String,
        status: Int,
        body: String,
    ): ArtifactId = write(ids.stepId(), owner, ArtifactType.HTTP, "$status ${body.take(MAX_BODY_CHARS)}".toByteArray())

    suspend fun screenshot(
        session: BrowserSession,
        owner: String,
    ): ArtifactId? = optional("screenshot") { session.screenshot() }?.let { write(ids.stepId(), owner, ArtifactType.SCREENSHOT, it) }

    private suspend fun write(
        step: StepId,
        owner: String,
        type: ArtifactType,
        bytes: ByteArray,
    ): ArtifactId {
        val record = artifacts.write(explorationId.evidenceKey, step, owner, type, bytes)
        records.saveArtifact(record)
        return record.artifactId
    }

    private suspend fun <T> optional(
        what: String,
        block: suspend () -> T,
    ): T? =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: BrowserActionException) {
            logger.warn { "Could not take the $what of $explorationId: ${e.message}" }
            null
        }

    private companion object {
        const val MAX_BODY_CHARS = 4_000
    }
}
