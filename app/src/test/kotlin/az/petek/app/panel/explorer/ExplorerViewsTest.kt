/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.app.panel.explorer

import az.petek.app.testing.ExplorerFixtures
import az.petek.browser.domain.RealtimeTransport
import az.petek.dashboard.domain.FindingSeverity
import az.petek.dashboard.domain.ModelChangeKind
import az.petek.dashboard.domain.Provenance
import az.petek.explorer.domain.ActionKind
import az.petek.explorer.domain.FieldModel
import az.petek.explorer.domain.RealtimeObservation
import az.petek.explorer.domain.SiteModelDiff
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import az.petek.explorer.domain.Provenance as ModelProvenance

class ExplorerViewsTest {
    @Test
    fun `a site model becomes pages with their forms, actions and real-time channels named by page`() {
        val model =
            ExplorerFixtures.model().copy(
                realtime =
                    listOf(
                        RealtimeObservation(
                            RealtimeTransport.SSE,
                            "GET /events",
                            setOf("announcements"),
                            setOf("admin"),
                            ModelProvenance.OBSERVED,
                            emptyList(),
                        ),
                    ),
            )

        val view = ExplorerViews.model(model)

        view.version shouldBe 1
        view.pages.map { it.urlPattern } shouldContainExactly listOf("/login", "/announcements")
        val page = view.pages.last()
        page.reachableBy shouldContainExactly listOf("admin", "employee")
        page.provenance shouldBe Provenance.OBSERVED
        page.forms
            .single()
            .fields
            .single()
            .required shouldBe true
        page.actions.single().kind shouldBe "CREATE"
        view.realtime.single().transport shouldBe "sse"
        view.realtime.single().pages shouldContainExactly listOf("/announcements")
    }

    @Test
    fun `findings keep their kind, severity, page and evidence`() {
        val finding = ExplorerViews.finding(ExplorerFixtures.finding().finding)

        finding.kind shouldBe "BROKEN_LINK"
        finding.severity shouldBe FindingSeverity.MEDIUM
        finding.detail shouldBe "Keçid /old 404 qaytarır"
    }

    @Test
    fun `ideas are ranked in three tiers and explained in the owner's words`() {
        ExplorerViews.tier(100) shouldBe 1
        ExplorerViews.tier(60) shouldBe 1
        ExplorerViews.tier(59) shouldBe 2
        ExplorerViews.tier(45) shouldBe 2
        ExplorerViews.tier(44) shouldBe 3

        val model =
            ExplorerFixtures.model(
                actions =
                    listOf(
                        ExplorerFixtures.action(
                            "approve",
                            "announcements",
                            ActionKind.APPROVE,
                            "Təsdiqlə",
                            setOf("manager"),
                            setOf("employee"),
                        ),
                    ),
            )
        val ideas = ExplorerViews.ideas(model, "təsdiq axınını yoxla")

        ideas.map { it.pattern } shouldContainExactly listOf("PERMISSION", "RACE")
        ideas.map { it.priority } shouldContainExactly listOf(1, 1)
        ideas.first().action shouldBe "Təsdiqlə"
        ideas.first().rationale shouldBe
            "'Təsdiqlə' (təsdiq): işçi bu əməliyyatı görmədi; server də onlara icazə verməməlidir. Təlimatınıza uyğundur."
        ideas.last().rationale shouldContain "yalnız biri uğurlu olmalıdır"
    }

    @Test
    fun `a model diff lists added, removed and changed pages, forms, fields and actions`() {
        val before =
            ExplorerFixtures.model(
                version = 1,
                pages =
                    listOf(
                        ExplorerFixtures.page("/login"),
                        ExplorerFixtures.page("/old"),
                        ExplorerFixtures.page("/announcements", forms = listOf(ExplorerFixtures.form())),
                    ),
                actions = listOf(ExplorerFixtures.action("gone", "old", name = "Köhnə")),
            )
        val changedForm =
            ExplorerFixtures.form(
                fields =
                    listOf(
                        FieldModel("Başlıq", "title", "text", false, "announcement-title", "#title"),
                        FieldModel("Mətn", "body", "textarea", true, "announcement-body", "#body"),
                    ),
            )
        val after =
            ExplorerFixtures.model(
                version = 2,
                pages =
                    listOf(
                        ExplorerFixtures.page("/login"),
                        ExplorerFixtures.page("/announcements", forms = listOf(changedForm)),
                        ExplorerFixtures.page("/tickets"),
                    ),
                actions = listOf(ExplorerFixtures.action("new", "tickets", name = "Ticket yarat")),
            )

        val view = ExplorerViews.diff(SiteModelDiff.between(before, after))

        view.fromVersion shouldBe 1
        view.toVersion shouldBe 2
        view.changes.map { Triple(it.kind, it.subject, it.name) } shouldContainExactly
            listOf(
                Triple(ModelChangeKind.ADDED, "PAGE", "/tickets"),
                Triple(ModelChangeKind.REMOVED, "PAGE", "/old"),
                Triple(ModelChangeKind.ADDED, "FIELD", "/announcements · [data-testid=\"announcement-submit\"] · Mətn"),
                Triple(ModelChangeKind.CHANGED, "FIELD", "/announcements · [data-testid=\"announcement-submit\"] · title"),
                Triple(ModelChangeKind.ADDED, "ACTION", "Ticket yarat"),
                Triple(ModelChangeKind.REMOVED, "ACTION", "Köhnə"),
            )
        view.changes[3].detail shouldBe "required: true -> false"
    }
}
