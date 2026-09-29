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

package az.petek.app.panel.explorer

import az.petek.app.config.ResolvedTarget
import az.petek.app.testing.PanelHarness
import az.petek.browser.domain.BrowserSessionFactory
import az.petek.browser.domain.SessionOptions
import az.petek.browser.testing.FakeBrowserSession
import az.petek.campaign.domain.Campaign
import az.petek.campaign.domain.TargetProfile
import az.petek.campaign.domain.TargetSpec
import az.petek.campaign.domain.Tenant
import az.petek.core.ids.AgentId
import az.petek.core.ids.RunId
import az.petek.core.ids.RunTag
import az.petek.core.model.RegistrationMode
import az.petek.core.model.Role
import az.petek.core.security.Secret
import az.petek.explorer.domain.ActionKind
import az.petek.explorer.domain.ExplorationId
import az.petek.explorer.domain.FieldModel
import az.petek.explorer.domain.FormModel
import az.petek.explorer.domain.PageModel
import az.petek.explorer.domain.Provenance
import az.petek.explorer.domain.SiteModel
import az.petek.explorer.domain.TestTargetVerdict
import az.petek.explorer.domain.UrlPatterns
import az.petek.identity.domain.Identity
import az.petek.identity.domain.IdentityPlan
import az.petek.identity.domain.IdentityStatus
import az.petek.orchestration.application.TeardownResult
import az.petek.orchestration.application.TeardownUseCase
import az.petek.orchestration.domain.RunOutcome
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

class TestCompanyRoleSessionsTest {
    @TempDir
    lateinit var dir: Path

    private val open = mutableListOf<PanelHarness>()
    private val campaigns = CopyOnWriteArrayList<Campaign>()
    private val progress = CopyOnWriteArrayList<String>()
    private val opened = CopyOnWriteArrayList<Pair<SessionOptions, FakeBrowserSession>>()
    private val factory =
        BrowserSessionFactory { options -> FakeBrowserSession(options.label).also { opened += options to it } }

    @AfterEach
    fun close() = open.forEach { it.close() }

    private fun harness(
        testToken: String? = "dev-token",
        tenant: Tenant? = null,
    ) = PanelHarness(
        dir,
        testToken = testToken,
        targets =
            listOfNotNull(
                tenant?.let {
                    ResolvedTarget(
                        TargetSpec("demo", URI("http://127.0.0.1:9"), tenant = it),
                        testToken = Secret("dev-token"),
                        accounts = emptyList(),
                    )
                },
            ),
    ).also { open += it }

    /** What the explorer's visitor walk saw (Faza 25.1): one page with a form of these fields. */
    private fun seen(vararg fields: String): SiteModel {
        val form =
            FormModel(
                "join form",
                ActionKind.REGISTER,
                fields.map { FieldModel(it, it, "text", true, "f-$it", "[data-testid=\"f-$it\"]", emptyList()) },
                "[data-testid=\"join-submit\"]",
                "POST",
                "/join",
                Provenance.OBSERVED,
                emptyList(),
            )
        val page =
            PageModel(
                id = UrlPatterns.pageId("/join"),
                urlPattern = "/join",
                title = "Qoşul",
                purpose = "",
                reachableBy = setOf("anonymous"),
                forms = listOf(form),
                testIds = emptyList(),
                linkCount = 1,
                loadMs = 100,
                provenance = Provenance.OBSERVED,
                evidence = emptyList(),
            )
        return SiteModel(
            version = 1,
            explorationId = ExplorationId("exp_seen"),
            target = URI("http://127.0.0.1:9"),
            createdAt = Instant.EPOCH,
            pages = listOf(page),
            actions = emptyList(),
            roles = emptyList(),
            realtime = emptyList(),
            unknowns = emptyList(),
        )
    }

    private fun request(
        panel: PanelHarness,
        allowWrites: Boolean = true,
        target: URI = panel.config.target,
    ) = RoleSessionRequest(target, allowWrites, listOf("Satış", "IT"))

    private fun sessions(
        panel: PanelHarness,
        testApi: TestApiProbe = TestApiProbe { null },
        setup: suspend (Campaign) -> SetupRun?,
    ) = TestCompanyRoleSessions(
        panel.panel.container,
        { campaign ->
            campaigns += campaign
            setup(campaign)
        },
        testApi,
    )

    @Test
    fun `nothing is written without the owner's permission, on another site or without a test token`() =
        runBlocking<Unit> {
            val panel = harness()
            val source = sessions(panel) { error("nothing may be created") }

            source.open(request(panel, allowWrites = false), factory) { progress += it }.note.shouldNotBeNull() shouldContain
                "Sınaq toxunuşu"
            source.open(request(panel, target = URI("https://other.test")), factory) { progress += it }.note.shouldNotBeNull() shouldContain
                "yalnız konfiqurasiya olunmuş hədəfdə"
            open.removeAt(0).close()
            val tokenless = harness(testToken = null)
            sessions(tokenless) { error("nothing may be created") }
                .open(request(tokenless), factory) { progress += it }
                .note
                .shouldNotBeNull() shouldContain "PETEK_TEST_TOKEN"

            campaigns.shouldBeEmpty()
            opened.shouldBeEmpty()
        }

    @Test
    fun `a target whose test API does not answer gets nothing written, whatever the token says`() =
        runBlocking<Unit> {
            val panel = harness()

            val roles =
                sessions(panel, testApi = { "hədəfdə test API-si yoxdur" }) { error("nothing may be created") }
                    .open(request(panel), factory) { progress += it }
            val real = TestCompanyRoleSessions(panel.panel.container, { error("nothing may be created") })

            roles.note shouldBe "Rollarla gəzinti buraxıldı: hədəfdə test API-si yoxdur"
            real.open(request(panel), factory) { }.note.shouldNotBeNull() shouldContain "test API-si cavab vermədi"
            campaigns.shouldBeEmpty()
        }

    @Test
    fun `no test company where the visitor's walk saw no way to join one, whatever the test API says`() =
        runBlocking<Unit> {
            val panel = harness()
            val source = sessions(panel) { error("nothing may be created") }

            val roles = source.open(request(panel).copy(seen = seen("email", "password")), factory) { progress += it }

            roles.note.shouldNotBeNull() shouldContain "şirkətə qoşulma yolu"
            campaigns.shouldBeEmpty()
            opened.shouldBeEmpty()
        }

    @Test
    fun `a site whose visitor walk shows a form to join by company code gets its test company`() =
        runBlocking<Unit> {
            val panel = harness()
            val source = sessions(panel) { null }

            source.open(request(panel).copy(seen = seen("email", "company_code")), factory) { progress += it }

            campaigns.single().settings.name shouldBe TestCompanyRoleSessions.NAME
        }

    @Test
    fun `the owner's word on the site's companies wins over what the visitor's walk saw`() =
        runBlocking<Unit> {
            val companies = harness(tenant = Tenant.COMPANY)
            sessions(companies) { null }.open(request(companies).copy(seen = seen("email")), factory) { progress += it }
            campaigns.single().settings.name shouldBe TestCompanyRoleSessions.NAME
            open.removeAt(0).close()

            val plain = harness(tenant = Tenant.NONE)
            val roles =
                sessions(plain) { error("nothing may be created") }
                    .open(request(plain).copy(seen = seen("email", "company_code")), factory) { progress += it }

            roles.note.shouldNotBeNull() shouldContain "tenant: none"
            campaigns.size shouldBe 1
        }

    @Test
    fun `the test company's active testers become logged-in sessions per role, closed when the exploration ends`() =
        runBlocking<Unit> {
            val panel = harness()
            val run = RunId("run_sessions")
            val source =
                sessions(panel) {
                    store(panel, run)
                    SetupRun(run, RunOutcome.PASSED)
                }

            val roles = source.open(request(panel), factory) { progress += it }

            roles.note.shouldBeNull()
            roles.sessions.keys.toList() shouldContainExactly listOf("admin", "manager", "employee")
            opened.map { it.first.label } shouldContainExactly listOf("explorer-admin", "explorer-manager", "explorer-employee")
            opened.all { it.first.storageState != null && it.first.baseUrl == panel.config.target } shouldBe true
            progress.last() shouldContain "Rol sessiyaları hazırdır: admin, menecer, işçi"
            val campaign = campaigns.single()
            campaign.settings.name shouldBe TestCompanyRoleSessions.NAME
            campaign.settings.departments shouldContainExactly listOf("Satış")
            campaign.setup.map { it.id } shouldContainExactly listOf("owner_signup", "seed", "join")
            campaign.steps.shouldBeEmpty()
            campaign.target shouldBe TargetProfile.DEFAULT
            progress.first() shouldContain "qeydiyyat axınları: docs/TARGET_CONTRACT.md default"
            roles.testCheck.check(panel.config.target).shouldBeInstanceOf<TestTargetVerdict.Refused>()

            roles.close()
            opened.all { it.second.closed } shouldBe true
        }

    @Test
    fun `the setup campaign signs up with the target profile of the site's own scenario`() =
        runBlocking<Unit> {
            val ownFile =
                PanelHarness.tinyCampaign(name = "real-site") +
                    """
                    target_profile:
                      selectors:
                        login.email: '#giris-email'
                    """.trimIndent() + "\n"
            val panel = PanelHarness(dir, scenarios = mapOf("real-site.yaml" to ownFile)).also { open += it }
            panel.backend.scenarios().map { it.name } shouldContainExactly listOf("real-site") // waits for the start-up import
            val source = sessions(panel) { SetupRun(RunId("run_profile"), RunOutcome.ABORTED) }

            source.open(request(panel), factory) { progress += it }

            val campaign = campaigns.single()
            campaign.target.selector("login.email") shouldBe "#giris-email"
            campaign.target.selector("login.password") shouldBe TargetProfile.DEFAULT.selector("login.password")
            progress.first() shouldContain "qeydiyyat axınları: real-site v1"
        }

    @Test
    fun `a busy slot, a failed setup or testers without a saved login give no sessions`() =
        runBlocking<Unit> {
            val panel = harness()

            sessions(panel) { null }.open(request(panel), factory) { }.note.shouldNotBeNull() shouldContain "Başqa run gedir"
            sessions(panel) { SetupRun(RunId("run_aborted"), RunOutcome.ABORTED) }
                .open(request(panel), factory) { }
                .note
                .shouldNotBeNull() shouldContain "Test şirkəti yaradıla bilmədi"
            sessions(panel) { SetupRun(RunId("run_stopped"), null) }
                .open(request(panel), factory) { }
                .note
                .shouldNotBeNull() shouldContain "dayandırıldı"
            sessions(panel) { SetupRun(RunId("run_empty"), RunOutcome.PASSED) }
                .open(request(panel), factory) { }
                .sessions
                .isEmpty() shouldBe true
            sessions(panel) { error("the test API is down") }
                .open(request(panel), factory) { }
                .note
                .shouldNotBeNull() shouldContain "the test API is down"
            opened.shouldBeEmpty()
        }

    @Test
    fun `stopping the exploration while the sessions open closes the opened ones and removes the test company`() =
        runBlocking<Unit> {
            val panel = harness()
            val run = RunId("run_stopped_while_opening")
            val removed = CopyOnWriteArrayList<RunId?>()
            val second = CompletableDeferred<Unit>()
            val slow =
                BrowserSessionFactory { options ->
                    val session = FakeBrowserSession(options.label).also { opened += options to it }
                    if (opened.size == 2) {
                        second.complete(Unit)
                        awaitCancellation()
                    }
                    session
                }
            val source =
                TestCompanyRoleSessions(
                    panel.panel.container,
                    {
                        store(panel, run)
                        SetupRun(run, RunOutcome.PASSED)
                    },
                    { null },
                    recording(removed),
                )

            val opening = async { source.open(request(panel), slow) { progress += it } }
            second.await()
            opening.cancelAndJoin()

            opening.isCancelled shouldBe true
            opened.first().second.closed shouldBe true
            removed shouldContainExactly listOf(run)
        }

    @Test
    fun `a failed or stopped setup run is removed once, and sessions that cannot open remove the company too`() =
        runBlocking<Unit> {
            val panel = harness()
            val removed = CopyOnWriteArrayList<RunId?>()
            val broken = BrowserSessionFactory { error("no browser") }

            TestCompanyRoleSessions(panel.panel.container, { SetupRun(RunId("run_stopped"), null) }, { null }, recording(removed))
                .open(request(panel), factory) { }
            TestCompanyRoleSessions(
                panel.panel.container,
                {
                    store(panel, RunId("run_no_browser"))
                    SetupRun(RunId("run_no_browser"), RunOutcome.PASSED)
                },
                { null },
                recording(removed),
            ).open(request(panel), broken) { }.note.shouldNotBeNull() shouldContain "Rol sessiyaları açıla bilmədi"

            removed shouldContainExactly listOf(RunId("run_stopped"), RunId("run_no_browser"))
        }

    private fun recording(removed: MutableList<RunId?>) =
        object : TeardownUseCase {
            override suspend fun teardown(runId: RunId?): TeardownResult {
                removed += runId
                return TeardownResult(runId, emptyList(), emptyList())
            }
        }

    /** What a finished setup run leaves: one active tester per role with a saved browser state. */
    private suspend fun store(
        panel: PanelHarness,
        run: RunId,
    ) {
        val identities = panel.panel.container.identities
        val testers =
            listOf(
                identity("a01", Role.ADMIN, RegistrationMode.OWNER),
                identity("a02", Role.MANAGER, RegistrationMode.INVITE),
                identity("a03", Role.EMPLOYEE, RegistrationMode.COMPANY_CODE),
            )
        identities.replaceAll(run, IdentityPlan(RunTag("k7x2"), testers))
        testers.forEach { tester ->
            val state = dir.resolve("${tester.agentId.value}.json").also { Files.writeString(it, "{}") }
            identities.updateStatus(run, tester.agentId, IdentityStatus.ACTIVE)
            identities.updateStorageState(run, tester.agentId, state.toString())
        }
    }

    private fun identity(
        agent: String,
        role: Role,
        registration: RegistrationMode,
    ) = Identity(
        AgentId(agent),
        agent,
        "$agent@test.portal.example",
        Secret("password-123"),
        "+994500000000",
        role,
        if (role == Role.ADMIN) null else "IT",
        registration,
    )
}
