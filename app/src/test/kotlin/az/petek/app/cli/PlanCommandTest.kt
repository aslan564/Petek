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

package az.petek.app.cli

import az.petek.app.testing.CliHarness
import az.petek.core.ids.RunId
import az.petek.core.model.RegistrationMode
import az.petek.evidence.domain.RunResult
import az.petek.identity.domain.IdentityStatus
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class PlanCommandTest {
    @TempDir
    lateinit var dir: Path

    private val campaign =
        """
        campaign:
          name: plan-demo
          testers: 4
          seed: 11
          names: [Əli]
          roles: {admin: 1, manager: 1, employee: 2}
          departments: [IT, HR]
          registration: {invite: 2, company_code: 1}
          budget: {max_steps_per_agent: 5, max_minutes: 2}
        setup:
          - id: signup
            actor: admin
            do: "Sign up"
        steps: []
        """

    @Test
    fun `plan prints every identity and stores the registry`() =
        runBlocking<Unit> {
            val cli = CliHarness(dir)
            cli.write("demo.yaml", campaign)

            val result = cli.run("plan", "demo.yaml")

            result.statusCode shouldBe 0
            result.stdout shouldContain "Campaign 'plan-demo' (seed 11)"
            result.stdout shouldContain "4 identities"
            listOf("Agent", "Name", "E-mail", "Role", "Department", "Registration", "Phone").forEach { result.stdout shouldContain it }
            listOf("a01", "a02", "a03", "a04", "admin", "manager", "employee", "owner", "invite", "company_code", "+99450")
                .forEach { result.stdout shouldContain it }
            result.stdout shouldContain "Əli"
            result.stdout shouldContain "@test.portal.example"
            result.stdout shouldContain "Nothing was executed against the target"
            val stored = cli.evidence { it.identities.findByRun(planRunIdIn(result.stdout)) }
            stored shouldHaveSize 4
            stored.all { it.status == IdentityStatus.PLANNED } shouldBe true
            stored.forEach { result.stdout shouldContain it.email }
        }

    @Test
    fun `a race that outnumbers the pacing limit is planned, with a warning`() =
        runBlocking<Unit> {
            val cli = CliHarness(dir)
            cli.write(
                "race.yaml",
                """
                campaign:
                  name: plan-race
                  testers: 4
                  seed: 11
                  roles: {admin: 1, manager: 3, employee: 0}
                  departments: [IT, HR]
                  registration: {invite: 3, company_code: 0}
                  budget: {max_steps_per_agent: 5, max_minutes: 2}
                  pacing: {max_parallel_actors: 2}
                steps:
                  - id: race
                    actor: manager[*]
                    parallel: true
                    do: "Approve the same ticket"
                    assert:
                      - only_one_succeeds: {request: "POST .*/approve"}
                """,
            )

            val result = cli.run("plan", "race.yaml")

            result.statusCode shouldBe 0
            result.stderr shouldContain "Warning: line"
            result.stderr shouldContain "race 'race' starts up to 3 testers at the same instant"
            result.stdout shouldContain "4 identities"
        }

    @Test
    fun `plan says which steps nobody would perform, wave by wave, as run does`() =
        runBlocking<Unit> {
            val cli = CliHarness(dir)
            cli.write(
                "waves.yaml",
                """
                campaign:
                  name: plan-waves
                  testers: 5
                  seed: 3
                  wave_size: 2
                  roles: {admin: 1, manager: 2, employee: 2}
                  departments: [IT, HR]
                  registration: {invite: 3, company_code: 1}
                  budget: {max_steps_per_agent: 5, max_minutes: 2}
                steps:
                  - id: second_manager
                    actor: manager[n=2]
                    do: "Open the page as the second manager"
                """,
            )

            val result = cli.run("plan", "waves.yaml")
            val answer = cli.run("--json", "plan", "waves.yaml")

            // One manager in each wave of two: the second manager of a wave never exists.
            val warning =
                "with campaign.wave_size 2 no tester matches 'manager[n=2]' in wave 1, every wave it starts in, so nobody " +
                    "performs step 'second_manager' (line 11) and the run fails for it (not_covered)."
            result.statusCode shouldBe 0
            result.stderr shouldContain "Warning: $warning"
            result.stdout shouldContain "5 identities"
            answer.stdout shouldContain warning
        }

    @Test
    fun `a campaign whose testers sign in with the owner's accounts is planned with them, with its warnings`() =
        runBlocking<Unit> {
            val cli = CliHarness(dir, environment = mapOf("NOTES_WRITER_PASSWORD" to "owner-writer-pass"))
            Files.createDirectories(dir.resolve("targets"))
            Files.writeString(
                dir.resolve("targets/notes.yaml"),
                """
                target:
                  name: notes
                  url: ${CliHarness.UNUSED_TARGET}
                  tenant: none
                  test_api: {mode: none}
                  accounts:
                    - {role: writer, name: Sahibin Yazarı, email: writer@owner.example, password: '${'$'}{NOTES_WRITER_PASSWORD}'}
                """.trimIndent() + "\n",
            )
            cli.write(
                "login.yaml",
                """
                campaign:
                  name: notes-login
                  tenant: none
                  testers: 4
                  seed: 12
                  wave_size: 2
                  roles: {writer: 2, reader: 2}
                  registration: {self: 3, login: 1}
                  budget: {max_steps_per_agent: 5, max_minutes: 2}
                steps:
                  - id: second_writer
                    actor: writer[n=2]
                    do: "Open the note as the second writer"
                """,
            )

            val result = cli.run("plan", "login.yaml")

            // Without the owner's account the registry of a `login` tester cannot be built, and plan said nothing else.
            result.stderr shouldNotContain "Identity registry cannot be built"
            result.statusCode shouldBe 0
            result.stdout shouldContain "writer@owner.example"
            result.stderr shouldContain "Warning: with campaign.wave_size 2 no tester matches 'writer[n=2]'"
            result.output shouldNotContain "owner-writer-pass"
        }

    @Test
    fun `a login campaign planned first still runs, and the owner's password is neither stored by the plan nor printed`() =
        runBlocking<Unit> {
            val cli = ownerLoginSite()

            val plan = cli.run("plan", "login.yaml")
            val planned = cli.evidence { it.identities.findByRun(planRunIdIn(plan.stdout)) }
            val storedByPlan = storedBytes(cli)
            val run = cli.run("run", "login.yaml")

            plan.statusCode shouldBe 0
            plan.stdout shouldContain "3 identities"
            plan.stdout shouldContain "writer@owner.example"
            plan.stdout shouldContain "except the 1 that sign in with the owner's accounts"
            planned.map { it.email } shouldNotContain "writer@owner.example"
            planned shouldHaveSize 2
            storedByPlan shouldNotContain OWNER_PASSWORD
            run.stderr shouldNotContain "IdentityConflictException"
            run.statusCode shouldBe 0
            cli.evidence { it.evidence.latest() }?.result shouldBe RunResult.PASSED
            val ran = cli.evidence { it.identities.findByRun(it.evidence.latest()!!.runId) }
            ran.single { it.registration == RegistrationMode.LOGIN }.email shouldBe "writer@owner.example"
            (plan.output + run.output) shouldNotContain OWNER_PASSWORD
        }

    @Test
    fun `a login campaign planned twice, with a run in between, prints the same registry each time`() =
        runBlocking<Unit> {
            val cli = ownerLoginSite()

            val first = cli.run("plan", "login.yaml")
            val run = cli.run("run", "login.yaml")
            val second = cli.run("plan", "login.yaml")
            val answer = cli.run("--json", "plan", "login.yaml")

            run.statusCode shouldBe 0
            second.stderr shouldNotContain "IdentityConflictException"
            second.statusCode shouldBe 0
            second.stdout shouldBe first.stdout
            answer.statusCode shouldBe 0
            answer.stdout shouldContain "\"email\":\"writer@owner.example\""
            answer.stdout shouldContain "\"registration\":\"login\""
            cli.evidence { it.identities.findByRun(planRunIdIn(second.stdout)) }.map { it.email } shouldNotContain
                "writer@owner.example"
            (first.output + second.output + answer.output) shouldNotContain OWNER_PASSWORD
        }

    @Test
    fun `passwords are never printed`() =
        runBlocking<Unit> {
            val cli = CliHarness(dir)
            cli.write("demo.yaml", campaign)

            val result = cli.run("plan", "demo.yaml")

            val stored = cli.evidence { it.identities.findByRun(planRunIdIn(result.stdout)) }
            stored.forEach { result.output shouldNotContain it.password.reveal() }
        }

    @Test
    fun `planning twice prints and stores the same registry`() =
        runBlocking<Unit> {
            val cli = CliHarness(dir)
            cli.write("demo.yaml", campaign)

            val first = cli.run("plan", "demo.yaml")
            val second = cli.run("plan", "demo.yaml")

            second.statusCode shouldBe 0
            second.stdout shouldBe first.stdout
            cli.evidence { it.identities.findByRun(planRunIdIn(first.stdout)) } shouldHaveSize 4
        }

    @Test
    fun `an invalid campaign prints every issue with its line and exits with 1`() =
        runBlocking<Unit> {
            val cli = CliHarness(dir)
            cli.write(
                "broken.yaml",
                """
                campaign:
                  testers: 3
                  seed: 1
                  roles: {admin: 1, manager: 0, employee: 1}
                  departments: [IT]
                  budget: {max_steps_per_agent: 5, max_minutes: 2}
                steps:
                  - id: look
                    actor: employee[*]
                    run: no_such_function
                """,
            )

            val result = cli.run("plan", "broken.yaml")

            result.statusCode shouldBe 1
            result.stderr shouldContain "Campaign is invalid"
            result.stderr shouldContain "line 4: roles add up to 2"
            result.stderr shouldContain "no_such_function"
            Files.exists(cli.dbPath) shouldBe false
        }

    @Test
    fun `a missing campaign file is reported`() =
        runBlocking<Unit> {
            val result = CliHarness(dir).run("plan", "absent.yaml")

            result.statusCode shouldBe 1
            result.stderr shouldContain "does not exist"
        }

    @Test
    fun `an invalid configuration exits with 2 and names the problem`() =
        runBlocking<Unit> {
            val cli = CliHarness(dir)
            cli.env.remove("PETEK_TARGET")
            cli.write("demo.yaml", campaign)

            val result = cli.run("plan", "demo.yaml")

            result.statusCode shouldBe 2
            result.stderr shouldContain "PETEK_TARGET is required"
            Files.exists(cli.dbPath) shouldBe false
        }

    @Test
    fun `the company portal campaign plans thirty testers`() =
        runBlocking<Unit> {
            val scenario =
                generateSequence(Path.of("").toAbsolutePath()) { it.parent }
                    .map { it.resolve("docs/examples/company-portal.yaml") }
                    .firstOrNull(Files::isRegularFile)
            assumeTrue(scenario != null, "docs/examples/company-portal.yaml is not reachable from the test's working directory")
            val cli = CliHarness(dir)

            val result = cli.run("plan", scenario!!.toString())

            result.statusCode shouldBe 0
            result.stdout shouldContain "30 identities"
            result.stdout shouldContain "a30"
        }

    /** A site whose profile gives its `writer` tester the owner's account, and a campaign one of whose writers signs in with it. */
    private fun ownerLoginSite(): CliHarness {
        val cli = CliHarness(dir, environment = mapOf("NOTES_WRITER_PASSWORD" to OWNER_PASSWORD))
        Files.createDirectories(dir.resolve("targets"))
        Files.writeString(
            dir.resolve("targets/notes.yaml"),
            """
            target:
              name: notes
              url: ${CliHarness.UNUSED_TARGET}
              tenant: none
              test_api: {mode: none}
              accounts:
                - {role: writer, name: Sahibin Yazarı, email: writer@owner.example, password: '${'$'}{NOTES_WRITER_PASSWORD}'}
            """.trimIndent() + "\n",
        )
        cli.write(
            "login.yaml",
            """
            campaign:
              name: notes-login
              tenant: none
              testers: 3
              seed: 12
              roles: {writer: 2, reader: 1}
              registration: {self: 2, login: 1}
              budget: {max_steps_per_agent: 5, max_minutes: 2}
            steps:
              - id: look
                actor: [writer[*], reader]
                do: "Open the notes"
            """,
        )
        return cli
    }

    /** Everything the commands left in the evidence directory (the database and its journal), as text. */
    private fun storedBytes(cli: CliHarness): String =
        Files.walk(cli.evidenceDir).use { files ->
            files.filter(Files::isRegularFile).toList().joinToString("\n") { String(Files.readAllBytes(it), Charsets.ISO_8859_1) }
        }

    private fun planRunIdIn(stdout: String): RunId = RunId(Regex("plan (plan_[0-9a-f]+_-?\\d+)").find(stdout)!!.groupValues[1])

    private companion object {
        const val OWNER_PASSWORD = "owner-writer-pass"
    }
}
