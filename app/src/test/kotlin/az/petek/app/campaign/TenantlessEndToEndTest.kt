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

package az.petek.app.campaign

import az.petek.app.config.ConfigLoader
import az.petek.app.config.IdentitySecretSource
import az.petek.app.di.AppContainer
import az.petek.app.di.AppOverrides
import az.petek.app.testing.CliHarness.Companion.done
import az.petek.app.testing.scriptedLlm
import az.petek.core.model.RegistrationMode
import az.petek.evidence.domain.StepStatus
import az.petek.evidence.domain.Verdict
import az.petek.faketarget.notes.FakeNotesServer
import az.petek.faketarget.notes.NotesBug
import az.petek.orchestration.domain.RunOptions
import az.petek.orchestration.domain.RunOutcome
import az.petek.orchestration.domain.RunSummary
import az.petek.orchestration.infrastructure.NoOpMonitorView
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Faza 13's proof: a campaign with `tenant: none` (its own roles, `self` and `guest` gates, no departments) runs end
 * to end in real Chromium against the second fake site, a notes application without companies or a test API. The
 * testers sign up through the default `sign_up` flow, the visitor stays anonymous, and the oracle check is
 * "N/A (no oracle)" instead of a failure.
 */
@Tag("e2e")
class TenantlessEndToEndTest {
    @TempDir
    lateinit var dir: Path

    private val sites = mutableListOf<FakeNotesServer>()

    @AfterEach
    fun stop() = sites.forEach { it.close() }

    private fun site(vararg bugs: NotesBug) = FakeNotesServer(bugs.toSet()).start().also { sites += it }

    /** Runs [CAMPAIGN] against [site] with the production object graph and real Chromium. */
    private suspend fun <T> run(
        site: FakeNotesServer,
        campaign: String = CAMPAIGN,
        extraEnv: String = "",
        check: suspend (AppContainer, RunSummary) -> T,
    ): T {
        val env =
            dir.resolve("notes.env").also {
                Files.writeString(
                    it,
                    """
                    PETEK_TARGET=${site.baseUrl}
                    PETEK_ORACLE=none
                    PETEK_MAILPIT_URL=http://127.0.0.1:9
                    PETEK_IDENTITY_SECRET=tenantless-e2e-secret
                    PETEK_BROWSER_HEADLESS=true
                    PETEK_EVIDENCE_DIR=evidence
                    """.trimIndent() + "\n" + extraEnv,
                )
            }
        val config = ConfigLoader(emptyMap(), dir, IdentitySecretSource { error("the test names its secret") }).load(env)
        val campaignFile = dir.resolve("notes.yaml").also { Files.writeString(it, campaign) }
        return AppContainer(config, AppOverrides(llm = scriptedLlm { done() }, monitor = NoOpMonitorView)).use { container ->
            val campaign = container.campaigns.execute(campaignFile, container.knownRunFunctions)
            check(container, container.campaignRunner(headless = true).run(campaign, RunOptions()))
        }
    }

    @Test
    fun `a site without companies is tested with its own roles, self sign-up, a visitor and blind checks, without an oracle`() =
        runBlocking<Unit> {
            val site = site()
            site.seedNote("owner@example.com", "Sahibin qeydi")

            run(site) { container, summary ->
                summary.outcome shouldBe RunOutcome.PASSED
                site.accounts shouldBe 2
                val identities = container.identities.findByRun(summary.runId)
                identities.map { it.registration } shouldContainExactlyInAnyOrder
                    listOf(RegistrationMode.SELF, RegistrationMode.SELF, RegistrationMode.GUEST)
                identities.map { it.department }.toSet() shouldBe setOf(null)
                val assertions = container.evidenceQuery.assertions(summary.runId)
                assertions.filter { it.type == "oracle" }.map { it.verdict } shouldBe List(2) { Verdict.NOT_APPLICABLE }
                val steps = container.evidenceQuery.steps(summary.runId)
                steps.single { it.action == "run site_health" }.status shouldBe StepStatus.PASSED
                steps.filter { it.action == "run direct_url" }.map { it.status }.toSet() shouldBe setOf(StepStatus.PASSED)
            }
        }

    @Test
    fun `someone else's note shown by its address is found by the blind direct-url check`() =
        runBlocking<Unit> {
            val site = site(NotesBug.FOREIGN_NOTE_VISIBLE)
            site.seedNote("owner@example.com", "Sahibin qeydi")

            run(site) { container, summary ->
                summary.outcome shouldBe RunOutcome.FAILED
                val refused =
                    container.evidenceQuery
                        .steps(summary.runId)
                        .filter { it.action == "run direct_url" && it.status == StepStatus.FAILED }
                refused.map { it.agentId?.value } shouldBe listOf("a02")
                refused.first().detail.orEmpty() shouldContain "access_not_refused"
            }
        }

    @Test
    fun `a login tester signs in with the owner's account from the site's profile, never with the explorer's`() =
        runBlocking<Unit> {
            val site = site()
            site.seedAccount("Sahibin Yazarı", "writer@owner.example", "owner-writer-pass")
            site.seedAccount("Kəşfiyyatçı", "explorer@owner.example", "owner-explorer-pass")
            Files.createDirectories(dir.resolve("targets"))
            Files.writeString(
                dir.resolve("targets/notes.yaml"),
                """
                target:
                  name: notes
                  url: ${site.baseUrl}
                  tenant: none
                  test_api: {mode: none}
                  accounts:
                    - {role: explorer, email: explorer@owner.example, password: '${'$'}{NOTES_EXPLORER_PASSWORD}'}
                    - {role: writer, name: Sahibin Yazarı, email: writer@owner.example, password: '${'$'}{NOTES_WRITER_PASSWORD}'}
                """.trimIndent() + "\n",
            )
            val env = "NOTES_WRITER_PASSWORD=owner-writer-pass\nNOTES_EXPLORER_PASSWORD=owner-explorer-pass\n"

            run(site, LOGIN_CAMPAIGN, env) { container, summary ->
                summary.outcome shouldBe RunOutcome.PASSED
                site.accounts shouldBe 3
                val login = container.identities.findByRun(summary.runId).single { it.registration == RegistrationMode.LOGIN }
                login.email shouldBe "writer@owner.example"
                container.identities.findByRun(summary.runId).none { it.email == "explorer@owner.example" } shouldBe true
            }
        }

    private companion object {
        val CAMPAIGN =
            """
            campaign:
              name: notes
              tenant: none
              testers: 3
              seed: 11
              roles: {writer: 2, reader: 1}
              registration: {self: 2, guest: 1}
              budget: {max_steps_per_agent: 5, max_minutes: 3}
            setup:
              - id: gates
                actor: [writer[*], reader]
                run: register_and_login
            steps:
              - id: who-am-i
                actor: writer[*]
                run: verify_identity
                assert:
                  - oracle: {path: /test/notes/latest, field: author, equals: "{self.email}"}
              - id: blind-health
                actor: writer[n=1]
                run: {function: site_health, args: {pages: "home,/register"}}
              - id: blind-direct-url
                actor: [writer[n=2], reader]
                run: {function: direct_url, args: {path: /notes/1, text: Sahibin qeydi}}
            """.trimIndent() + "\n"

        /** One writer signs up, one takes the owner's writer account, the reader visits. */
        val LOGIN_CAMPAIGN =
            """
            campaign:
              name: notes-login
              tenant: none
              testers: 3
              seed: 12
              roles: {writer: 2, reader: 1}
              registration: {self: 1, login: 1, guest: 1}
              budget: {max_steps_per_agent: 5, max_minutes: 3}
            setup:
              - id: gates
                actor: [writer[*], reader]
                run: register_and_login
            steps:
              - id: who-am-i
                actor: writer[*]
                run: verify_identity
            """.trimIndent() + "\n"
    }
}
