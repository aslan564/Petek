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
import az.petek.identity.domain.IdentityStatus
import io.kotest.matchers.collections.shouldHaveSize
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

    private fun planRunIdIn(stdout: String): RunId = RunId(Regex("plan (plan_[0-9a-f]+_-?\\d+)").find(stdout)!!.groupValues[1])
}
