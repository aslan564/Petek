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
import az.petek.app.testing.ExplorerFixtures.T0
import az.petek.core.time.HarnessTimestamp
import az.petek.dashboard.domain.ExplorationStatus
import az.petek.dashboard.domain.PanelBudget
import az.petek.dashboard.domain.PhaseState
import az.petek.dashboard.domain.TestIdeaView
import az.petek.explorer.domain.ActionKind
import az.petek.explorer.domain.ExplorationPhase
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test
import az.petek.explorer.domain.ExplorationStatus as ModelStatus

class ExplorationTrackerTest {
    private val start = HarnessTimestamp(T0, 0)
    private val budget = PanelBudget(maxMinutes = 30, maxStepsPerAgent = 40, maxPages = 40)

    private fun tracker(previous: Int? = null) = ExplorationTracker("https://kadro.test", "Elanları yoxla", budget, start, previous)

    private fun at(seconds: Long) = HarnessTimestamp(T0.plusSeconds(seconds), seconds * 1_000_000_000)

    @Test
    fun `before the explorer starts the view is running, preparing, with every phase pending`() {
        val view = tracker().view(at(3))

        view.id shouldBe ExplorationTracker.PREPARING_ID
        view.status shouldBe ExplorationStatus.RUNNING
        view.elapsedMs shouldBe 3_000
        view.phases.map { it.state } shouldContainExactly listOf(PhaseState.PENDING, PhaseState.PENDING, PhaseState.PENDING)
        view.visited.shouldBeEmpty()
        view.currentPage.shouldBeNull()
        view.model.version shouldBe 1
    }

    @Test
    fun `phases move from running to done, pages count in the running phase and skipped phases say why in Azerbaijani`() {
        val tracker = tracker()
        tracker.apply(ExplorerFixtures.started())
        tracker.apply(ExplorerFixtures.phaseStarted(ExplorationPhase.ANONYMOUS))
        tracker.apply(ExplorerFixtures.visited("/login"))
        tracker.apply(ExplorerFixtures.visited("/register"))
        tracker.apply(ExplorerFixtures.phaseSkipped(ExplorationPhase.ROLE_BASED, "no logged-in sessions were given"))
        tracker.apply(ExplorerFixtures.phaseSkipped(ExplorationPhase.TRIAL_TOUCH, "allowWrites is false"))

        val running = tracker.view(at(5))
        running.id shouldBe "exp_1"
        running.phases.map { it.state } shouldContainExactly listOf(PhaseState.RUNNING, PhaseState.SKIPPED, PhaseState.SKIPPED)
        running.phases.first().pagesVisited shouldBe 2
        running.phases.first().roles shouldContainExactly listOf("anonymous")
        running.activity.first().kind shouldBe ExplorationTracker.PHASE_SKIPPED
        running.activity.first().text shouldBe "Sınaq toxunuşu buraxıldı: “Sınaq toxunuşu” seçilməyib (kəşfiyyatçı heç nə göndərmir)"
        running.activity[1].text shouldBe "Rollarla gəzinti buraxıldı: daxil olmuş sessiya yoxdur"

        tracker.apply(ExplorerFixtures.finished())
        val done = tracker.view(at(60))
        done.status shouldBe ExplorationStatus.FINISHED
        done.elapsedMs shouldBe 42_000
        done.phases.first().state shouldBe PhaseState.DONE
        done.message.shouldBeNull()
        done.activity.first().text shouldStartWith "Kəşfiyyat bitdi: 2 səhifə"
    }

    @Test
    fun `the page budget shown is per crawl pass times the passes known so far`() {
        val tracker = tracker()
        tracker.view(at(1)).budget.maxPages shouldBe 40

        tracker.apply(ExplorerFixtures.started())
        tracker.apply(ExplorerFixtures.phaseStarted(ExplorationPhase.ANONYMOUS))
        tracker.view(at(2)).budget.maxPages shouldBe 40
        tracker.apply(ExplorerFixtures.phaseStarted(ExplorationPhase.ROLE_BASED, roles = listOf("admin", "employee", "manager")))
        tracker.apply(ExplorerFixtures.phaseStarted(ExplorationPhase.TRIAL_TOUCH, roles = listOf("admin", "employee", "manager")))

        val view = tracker.view(at(3))
        view.budget.maxPages shouldBe 160
        view.budget.maxMinutes shouldBe 30
    }

    @Test
    fun `visited pages are newest first, the current page is the latest and the live model groups actions by page`() {
        val tracker = tracker(previous = 2)
        tracker.apply(ExplorerFixtures.started())
        tracker.apply(ExplorerFixtures.phaseStarted(ExplorationPhase.ANONYMOUS))
        tracker.apply(ExplorerFixtures.visited("/login"))
        tracker.apply(ExplorerFixtures.visited("/announcements", role = "admin"))
        tracker.apply(ExplorerFixtures.visited("/announcements", role = "employee"))
        tracker.apply(ExplorerFixtures.discovered(ExplorerFixtures.action("announcement-submit", "announcements")))

        val view = tracker.view(at(9))
        view.visited.map { it.visitedAs } shouldContainExactly listOf("employee", "admin", "anonymous")
        view.currentPage.shouldNotBeNull().visitedAs shouldBe "employee"
        view.currentPage?.screenshotArtifactId?.value shouldBe "art_/announcements"
        view.model.version shouldBe 3
        view.model.pages.map { it.urlPattern } shouldContainExactly listOf("/login", "/announcements")
        val announcements = view.model.pages.last()
        announcements.reachableBy shouldContainExactly listOf("admin", "employee")
        announcements.actions.single().name shouldBe "Elan yarat"
        view.activity.first().text shouldBe "Elan yarat (yaratma) · /announcements"
        view.ideas.map(TestIdeaView::pattern) shouldContainExactly listOf("HAPPY_PATH", "IDEMPOTENCY", "BOUNDARY")
    }

    @Test
    fun `findings and questions are listed, and an answer is shown with the question and in the instructions`() {
        val tracker = tracker()
        tracker.apply(ExplorerFixtures.started())
        tracker.apply(ExplorerFixtures.finding())
        tracker.apply(ExplorerFixtures.unknown())

        tracker
            .view(at(5))
            .unknowns
            .single()
            .answer
            .shouldBeNull()
        tracker.unknown("u1").shouldNotBeNull().question shouldBe "Şirkət kodu haradan alınır?"

        tracker.answered("u1", "Admin paneldə göstərilir", "Elanları yoxla\n\ncavablar")
        val view = tracker.view(at(6))

        view.unknowns.single().answer shouldBe "Admin paneldə göstərilir"
        view.instructions shouldBe "Elanları yoxla\n\ncavablar"
        view.findings.single().kind shouldBe "BROKEN_LINK"
        view.activity.map { it.kind } shouldContainExactly
            listOf(ExplorationTracker.UNKNOWN_RAISED, ExplorationTracker.FINDING_RECORDED, ExplorationTracker.STARTED)
    }

    @Test
    fun `the stored model replaces the live one with its forms, ideas and the draft when the exploration ends`() {
        val tracker = tracker()
        tracker.apply(ExplorerFixtures.started())
        tracker.apply(ExplorerFixtures.finished())
        val model = ExplorerFixtures.model()

        tracker.finished(model, ExplorerViews.ideas(model, null), "campaign:\n  name: x\n")
        val view = tracker.view(at(50))

        view.model.pages.map { it.urlPattern } shouldContainExactly listOf("/login", "/announcements")
        view.model.pages
            .last()
            .forms
            .single()
            .fields
            .single()
            .label shouldBe "Başlıq"
        view.model.pages
            .last()
            .actions
            .single()
            .forbiddenRoles shouldContainExactly listOf("employee")
        view.draftYaml shouldBe "campaign:\n  name: x\n"
        view.ideas.first().pattern shouldBe "HAPPY_PATH"
        view.ideas.any { it.pattern == "PERMISSION" && it.rationale.contains("işçi bu əməliyyatı görmədi") } shouldBe true

        tracker.drafted("campaign:\n  name: y\n")
        tracker.view(at(51)).draftYaml shouldBe "campaign:\n  name: y\n"
    }

    @Test
    fun `the elapsed time includes preparing the role sessions and the explorer's notes are shown translated`() {
        val tracker = tracker()
        tracker.apply(ExplorerFixtures.started(seconds = 18))
        tracker.apply(
            ExplorerFixtures.finished(
                ExplorerFixtures.summary(durationMs = 42_000, notes = listOf("Trial touch allowed: company c1 is_test=true", "other")),
                seconds = 60,
            ),
        )

        val view = tracker.view(at(90))
        view.elapsedMs shouldBe 60_000
        view.activity.map { it.text } shouldContainExactly
            listOf(
                "Kəşfiyyat bitdi: 2 səhifə, 1 əməliyyat, 1 form, 1 tapıntı",
                "other",
                "Sınaq toxunuşuna icazə verildi: company c1 is_test=true",
                "Kəşfiyyat başladı: kadro.test · Anonim gəzinti, Rollarla gəzinti, Sınaq toxunuşu",
            )
    }

    @Test
    fun `timed out, cancelled and page-budget endings explain themselves`() {
        val timedOut = tracker().apply { apply(ExplorerFixtures.finished(ExplorerFixtures.summary(ModelStatus.TIMED_OUT))) }
        timedOut.view(at(1)).status shouldBe ExplorationStatus.TIMED_OUT
        timedOut.view(at(1)).message shouldBe "Vaxt büdcəsi bitdi; öyrənilənlər saxlanıldı."

        val cancelled = tracker().apply { apply(ExplorerFixtures.finished(ExplorerFixtures.summary(ModelStatus.CANCELLED))) }
        cancelled.view(at(1)).status shouldBe ExplorationStatus.CANCELLED

        val full = tracker().apply { apply(ExplorerFixtures.finished(ExplorerFixtures.summary(pageBudgetReached = true))) }
        full.view(at(1)).status shouldBe ExplorationStatus.FINISHED
        full.view(at(1)).message shouldBe "Səhifə limiti (40) doldu; bəzi səhifələrə baxılmadı."
    }

    @Test
    fun `a failure outside the explorer or a stop before it started ends the view once`() {
        val failed = tracker()
        failed.failed(at(7), "Chromium başlamadı")
        failed.failed(at(9), "ikinci")
        failed.view(at(20)).status shouldBe ExplorationStatus.FAILED
        failed.view(at(20)).elapsedMs shouldBe 7_000
        failed.view(at(20)).message shouldBe "Kəşfiyyat alınmadı: Chromium başlamadı"

        val cancelled = tracker()
        cancelled.cancelled(at(2))
        cancelled.running shouldBe false
        cancelled.view(at(3)).status shouldBe ExplorationStatus.CANCELLED
    }

    @Test
    fun `the explorer's own failure event keeps what was learned and names the reason`() {
        val tracker = tracker()
        tracker.apply(ExplorerFixtures.started())
        tracker.apply(ExplorerFixtures.phaseStarted(ExplorationPhase.ANONYMOUS))
        tracker.apply(
            az.petek.explorer.domain.ExplorationEvent
                .Failed(ExplorerFixtures.header(9), "browser crashed", 1),
        )

        val view = tracker.view(at(12))
        view.status shouldBe ExplorationStatus.FAILED
        view.message shouldContain "browser crashed"
        view.phases.first().state shouldBe PhaseState.DONE
    }

    @Test
    fun `a replayed exploration whose process died is shown as interrupted`() {
        val tracker = tracker()
        tracker.apply(ExplorerFixtures.started())
        tracker.interrupted(T0.plusSeconds(90))

        val view = tracker.view(at(500))
        view.status shouldBe ExplorationStatus.FAILED
        view.elapsedMs shouldBe 90_000
        view.message shouldContain "yarımçıq"
    }

    @Test
    fun `lists are bounded so a huge site cannot grow the view without end`() {
        val tracker = tracker()
        tracker.apply(ExplorerFixtures.started())
        repeat(ExplorationTracker.VISITED_LIMIT + 20) { tracker.apply(ExplorerFixtures.visited("/p$it", screenshot = null)) }

        val view = tracker.view(at(1))
        view.visited shouldHaveSize ExplorationTracker.VISITED_LIMIT
        view.activity shouldHaveSize ExplorationTracker.ACTIVITY_LIMIT
        view.visited.first().url shouldBe "https://kadro.test/p${ExplorationTracker.VISITED_LIMIT + 19}"
    }

    @Test
    fun `an action on a page the live model has not seen yet still shows up under its page id`() {
        val tracker = tracker()
        tracker.apply(ExplorerFixtures.started())
        tracker.apply(ExplorerFixtures.discovered(ExplorerFixtures.action("approve", "tickets-id", ActionKind.APPROVE, "Təsdiqlə")))

        tracker
            .view(at(1))
            .activity
            .first()
            .text shouldBe "Təsdiqlə (təsdiq) · tickets-id"
    }
}
