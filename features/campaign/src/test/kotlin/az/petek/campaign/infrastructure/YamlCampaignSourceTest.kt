package az.petek.campaign.infrastructure

import az.petek.campaign.domain.ActorSelector
import az.petek.campaign.domain.AssertionSpec
import az.petek.campaign.domain.Campaign
import az.petek.campaign.domain.CampaignValidationException
import az.petek.campaign.domain.EmitSpec
import az.petek.campaign.domain.IdSource
import az.petek.campaign.domain.OnFail
import az.petek.campaign.domain.OracleCondition
import az.petek.campaign.domain.RegistrationQuota
import az.petek.campaign.domain.RequestPattern
import az.petek.campaign.domain.StepAction
import az.petek.campaign.domain.TargetProfile
import az.petek.campaign.domain.ValidationIssue
import az.petek.campaign.domain.WaitForSpec
import az.petek.core.model.RegistrationMode
import az.petek.core.model.Role
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class YamlCampaignSourceTest {
    @TempDir
    lateinit var dir: Path

    private fun write(
        yaml: String,
        name: String = "smoke.yaml",
    ): Path = dir.resolve(name).also { Files.writeString(it, yaml.trimIndent()) }

    private fun load(
        yaml: String,
        name: String = "smoke.yaml",
        targetOverride: URI? = null,
    ): Campaign = YamlCampaignSource(targetOverride).load(write(yaml, name))

    private fun issues(
        yaml: String,
        targetOverride: URI? = null,
    ): List<ValidationIssue> = shouldThrow<CampaignValidationException> { load(yaml, targetOverride = targetOverride) }.issues

    private fun issue(
        yaml: String,
        fragment: String,
    ): ValidationIssue {
        val all = issues(yaml)
        val matching = all.filter { fragment in it.message }
        check(matching.size == 1) { "expected one issue containing '$fragment', got:\n${all.joinToString("\n")}" }
        return matching.single()
    }

    /** A complete `campaign:` section (lines 1-7) that tests append steps to. */
    private val header =
        """
        campaign:
          target: https://staging.kadrohr.test
          testers: 3
          seed: 7
          roles: {admin: 1, manager: 0, employee: 2}
          departments: [IT]
          budget: {max_steps_per_agent: 10, max_minutes: 5}
        """.trimIndent()

    private fun withSteps(steps: String): String = header + "\n" + steps.trimIndent()

    @Nested
    inner class Defaults {
        private val minimal =
            withSteps(
                """
                setup:
                  - actor: admin
                    run: register_owner
                steps:
                  - actor: admin
                    do: "Elan yarat"
                    emits: created
                  - actor: employee
                    wait_for: created
                    assert:
                      - visible_text: {text: "Elan"}
                      - http_status: {path: /api/x, equals: 200}
                """,
            )

        private val campaign by lazy { load(minimal, name = "smoke-test.yaml") }

        @Test
        fun `the name defaults to the file name without extension`() {
            campaign.settings.name shouldBe "smoke-test"
        }

        @Test
        fun `optional settings take their defaults`() {
            campaign.settings.names.shouldBeEmpty()
            campaign.settings.onFail shouldBe OnFail.CONTINUE
            campaign.settings.registration shouldBe RegistrationQuota(invite = 1, companyCode = 1)
            campaign.target shouldBe TargetProfile.DEFAULT
        }

        @Test
        fun `step ids are generated per phase`() {
            campaign.setup.map { it.id } shouldContainExactly listOf("setup-1")
            campaign.steps.map { it.id } shouldContainExactly listOf("step-1", "step-2")
        }

        @Test
        fun `steps default to sequential, no own on_fail and no emits`() {
            val waiting = campaign.steps[1]
            waiting.parallel shouldBe false
            waiting.onFail shouldBe null
            waiting.emits shouldBe null
            waiting.action shouldBe StepAction.None
        }

        @Test
        fun `wait_for, visible_text and http_status take their defaults`() {
            val waiting = campaign.steps[1]
            waiting.waitFor shouldBe WaitForSpec("created", 30.seconds)
            waiting.assertions shouldContainExactly
                listOf(AssertionSpec.VisibleText("Elan", 5.seconds), AssertionSpec.HttpStatus("/api/x", "GET", 200))
        }

        @Test
        fun `the step line is the line of its list item`() {
            campaign.setup.single().line shouldBe 9
            campaign.steps.map { it.line } shouldContainExactly listOf(12, 15)
        }
    }

    @Nested
    inner class Registration {
        private fun registrationFor(
            managers: Int,
            employees: Int,
        ): RegistrationQuota =
            load(
                """
                campaign:
                  target: https://staging.kadrohr.test
                  testers: ${1 + managers + employees}
                  seed: 1
                  roles: {admin: 1, manager: $managers, employee: $employees}
                  departments: [IT]
                  budget: {max_steps_per_agent: 10, max_minutes: 5}
                steps:
                  - actor: admin
                    do: x
                """,
            ).settings.registration

        @Test
        fun `an omitted split gives invitations the odd tester`() {
            registrationFor(managers = 5, employees = 24) shouldBe RegistrationQuota(invite = 15, companyCode = 14)
        }

        @Test
        fun `an even number of non-admins splits evenly`() {
            registrationFor(managers = 2, employees = 8) shouldBe RegistrationQuota(invite = 5, companyCode = 5)
        }

        @Test
        fun `an omitted split still invites every manager`() {
            registrationFor(managers = 5, employees = 1) shouldBe RegistrationQuota(invite = 5, companyCode = 1)
            registrationFor(managers = 3, employees = 0) shouldBe RegistrationQuota(invite = 3, companyCode = 0)
            registrationFor(managers = 4, employees = 4) shouldBe RegistrationQuota(invite = 4, companyCode = 4)
        }

        @Test
        fun `no non-admins means nobody joins`() {
            registrationFor(managers = 0, employees = 0) shouldBe RegistrationQuota(invite = 0, companyCode = 0)
        }

        @Test
        fun `an explicit split is kept`() {
            val campaign =
                load(
                    header.replace("  departments: [IT]", "  departments: [IT]\n  registration: {invite: 0, company_code: 2}") +
                        "\nsteps: []",
                )
            campaign.settings.registration shouldBe RegistrationQuota(invite = 0, companyCode = 2)
        }
    }

    @Nested
    inner class Target {
        private val withoutTarget = withSteps("steps: []").replace("  target: https://staging.kadrohr.test\n", "")

        @Test
        fun `the override wins over the file`() {
            load(withSteps("steps: []"), targetOverride = URI("http://localhost:8080")).settings.target shouldBe
                URI("http://localhost:8080")
        }

        @Test
        fun `the override makes the target optional`() {
            load(withoutTarget, targetOverride = URI("http://localhost:8080")).settings.target shouldBe URI("http://localhost:8080")
        }

        @Test
        fun `a missing target without override is an error on the campaign line`() {
            val issue = issues(withoutTarget).single()
            issue.line shouldBe 1
            issue.message shouldContain "missing required key 'target'"
        }

        @Test
        fun `a malformed target is an error on its line`() {
            val issue = issues(withSteps("steps: []").replace("https://staging.kadrohr.test", "\"http://bad host\"")).single()
            issue.line shouldBe 2
            issue.message shouldContain "'campaign.target' is not a valid URL"
        }
    }

    @Nested
    inner class LongForms {
        @Test
        fun `run takes a function with arguments`() {
            val campaign =
                load(
                    withSteps(
                        """
                        steps:
                          - actor: employee[reg=invite]
                            run: {function: login, args: {as: "{self.email}", retries: 3, remember: true}}
                        """,
                    ),
                )
            campaign.steps.single().action shouldBe
                StepAction.Run("login", mapOf("as" to "{self.email}", "retries" to "3", "remember" to "true"))
            campaign.steps
                .single()
                .actors.selectors shouldContainExactly
                listOf(ActorSelector(Role.EMPLOYEE, registration = RegistrationMode.INVITE))
        }

        @Test
        fun `emits and wait_for accept maps`() {
            val campaign =
                load(
                    withSteps(
                        """
                        steps:
                          - id: make
                            actor: admin
                            do: x
                            emits: {event: made, id_from: {url_regex: "/items/(\\d+)"}}
                          - id: see
                            actor: [employee[n=1], employee[n=2]]
                            wait_for: {event: made, timeout_s: 2.5}
                            parallel: true
                            on_fail: abort
                            assert:
                              - only_one_succeeds: true
                        """,
                    ),
                )
            campaign.steps[0].emits shouldBe EmitSpec("made", IdSource.UrlRegex("/items/(\\d+)"))
            val see = campaign.steps[1]
            see.waitFor shouldBe WaitForSpec("made", 2500.milliseconds)
            see.parallel shouldBe true
            see.onFail shouldBe OnFail.ABORT
            see.actors.raw shouldBe "employee[n=1] | employee[n=2]"
            see.assertions shouldContainExactly listOf(AssertionSpec.OnlyOneSucceeds())
        }

        @Test
        fun `only_one_succeeds takes a request pattern and an oracle condition`() {
            val campaign =
                load(
                    withSteps(
                        """
                        steps:
                          - id: race
                            actor: [manager[IT], manager[HR]]
                            parallel: true
                            do: approve
                            assert:
                              - only_one_succeeds: {request: "post   .+/tickets/.+/approve"}
                              - only_one_succeeds:
                                  request: "* /api/.*"
                                  oracle: {path: "/test/tickets/{last_id}", field: status, equals: approved}
                              - only_one_succeeds: {oracle: {path: /test/tickets/7}}
                              - only_one_succeeds: {}
                        """,
                    ),
                )

            campaign.steps.single().assertions shouldContainExactly
                listOf(
                    AssertionSpec.OnlyOneSucceeds(RequestPattern("POST", ".+/tickets/.+/approve")),
                    AssertionSpec.OnlyOneSucceeds(
                        RequestPattern(null, "/api/.*"),
                        OracleCondition("/test/tickets/{last_id}", "status", "approved"),
                    ),
                    AssertionSpec.OnlyOneSucceeds(oracle = OracleCondition("/test/tickets/7")),
                    AssertionSpec.OnlyOneSucceeds(),
                )
        }

        @Test
        fun `every id source kind is supported`() {
            val campaign =
                load(
                    withSteps(
                        """
                        target_profile:
                          paths: {announcements: /news}
                          selectors: {login.email: "#email"}
                          id_sources:
                            a: {url_regex: "/a/(\\d+)"}
                            b: {oracle: {path: "/test/b/latest?by={self.email}", field: id}}
                            c: {dom: {selector: "li:first-child", attribute: data-id}}
                            d: {agent: true}
                        steps: []
                        """,
                    ),
                )
            campaign.target.paths shouldBe mapOf("announcements" to "/news")
            campaign.target.path("announcements") shouldBe "/news"
            campaign.target.selector("login.email") shouldBe "#email"
            campaign.target.idSources shouldBe
                mapOf(
                    "a" to IdSource.UrlRegex("/a/(\\d+)"),
                    "b" to IdSource.OracleField("/test/b/latest?by={self.email}", "id"),
                    "c" to IdSource.DomAttribute("li:first-child", "data-id"),
                    "d" to IdSource.AgentReport,
                )
        }

        @Test
        fun `every assertion kind is supported and scalars become text`() {
            val campaign =
                load(
                    withSteps(
                        """
                        steps:
                          - actor: employee
                            assert:
                              - visible_text: {text: Salam, within_s: 1}
                              - latency_max: {ms: 1500}
                              - not_visible: {text: Approve}
                              - oracle: {path: /test/x, field: count, equals: 3}
                              - oracle: {path: /test/y, field: done, equals: true}
                              - oracle: {path: /test/z, contains: "{self.email}"}
                              - http_status: {path: /api/x, method: delete, equals: 404}
                              - count: {selector: li, equals: 0}
                        """,
                    ),
                )
            campaign.steps.single().assertions shouldContainExactly
                listOf(
                    AssertionSpec.VisibleText("Salam", 1.seconds),
                    AssertionSpec.LatencyMax(1500.milliseconds),
                    AssertionSpec.NotVisible("Approve", null),
                    AssertionSpec.Oracle("/test/x", "count", "3", null),
                    AssertionSpec.Oracle("/test/y", "done", "true", null),
                    AssertionSpec.Oracle("/test/z", null, null, "{self.email}"),
                    AssertionSpec.HttpStatus("/api/x", "DELETE", 404),
                    AssertionSpec.Count("li", 0),
                )
        }

        @Test
        fun `tags are transparent and multi-line text is kept`() {
            val campaign =
                load(
                    withSteps(
                        """
                        steps:
                          - id: !!str 42
                            do: |
                              Birinci sətir
                              İkinci sətir
                            actor: admin
                        """,
                    ),
                )
            campaign.steps.single().id shouldBe "42"
            campaign.steps.single().action shouldBe StepAction.Do("Birinci sətir\nİkinci sətir\n")
        }

        @Test
        fun `a byte order mark is ignored`() {
            val file = dir.resolve("bom.yaml")
            Files.write(file, byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + withSteps("steps: []").toByteArray())
            YamlCampaignSource().load(file).settings.testers shouldBe 3
        }
    }

    @Nested
    inner class Errors {
        @Test
        fun `an unknown key is an error on its line`() {
            val issue = issue(withSteps("steps:\n  - actor: admin\n    do: x\n    wait_fr: created"), "unknown key")
            issue.line shouldBe 11
            issue.message shouldContain "unknown key 'wait_fr' in 'steps[0]'"
            issue.message shouldContain "allowed: id, actor, do, run, emits, wait_for, parallel, on_fail, assert"
        }

        @Test
        fun `unknown keys are errors at every level`() {
            issue(header.replace("  seed: 7", "  seed: 7\n  sead: 8") + "\nsteps: []", "unknown key 'sead' in 'campaign'").line shouldBe 5
            issue(withSteps("stepz: []"), "unknown key 'stepz' in the campaign file").line shouldBe 8
            issue(header.replace("employee: 2}", "employee: 2, intern: 1}") + "\nsteps: []", "unknown key 'intern'").line shouldBe 5
            issue(
                withSteps("steps:\n  - actor: admin\n    assert:\n      - count: {selector: li, equals: 1, within_s: 3}"),
                "unknown key 'within_s' in 'steps[0].assert[0].count'",
            ).line shouldBe 11
        }

        @Test
        fun `wrong types are errors on their lines`() {
            val yaml =
                """
                campaign:
                  target: https://staging.kadrohr.test
                  testers: many
                  seed: 7
                  roles: [1, 2]
                  departments: IT
                  budget: {max_steps_per_agent: 10, max_minutes: soon}
                  on_fail: retry
                steps:
                  - actor: admin
                    parallel: sometimes
                    wait_for: {event: x, timeout_s: long}
                """
            val found = issues(yaml).associate { it.message to it.line }
            found["'campaign.testers' must be an integer, was 'many'"] shouldBe 3
            found["'campaign.roles' must be a map with keys admin, manager, employee"] shouldBe 5
            found["'campaign.departments' must be a list"] shouldBe 6
            found["'campaign.budget.max_minutes' must be an integer, was 'soon'"] shouldBe 7
            found["'campaign.on_fail' must be continue or abort, was 'retry'"] shouldBe 8
            found["'steps[0].parallel' must be true or false, was 'sometimes'"] shouldBe 11
            found["'steps[0].wait_for.timeout_s' must be a number of seconds, was 'long'"] shouldBe 12
        }

        @Test
        fun `issues are sorted by line`() {
            val lines = issues(withSteps("steps: {}").replace("testers: 3", "testers: x")).map { it.line }
            lines shouldBe lines.sortedBy { it }
        }

        @Test
        fun `missing required keys point at their section`() {
            val issue = issue(header.replace("  seed: 7\n", "") + "\nsteps: []", "missing required key 'seed'")
            issue.line shouldBe 1
            issue(withSteps("steps:\n  - do: x"), "missing required key 'actor' in 'steps[0]'").line shouldBe 9
            issue("steps: []", "missing required key 'campaign' in the campaign file").line shouldBe 1
        }

        @Test
        fun `a key without a value is reported as such`() {
            issue(withSteps("steps:\n  - actor:\n    do: x"), "'steps[0].actor' has no value").line shouldBe 9
        }

        @Test
        fun `actor grammar errors carry the actor line`() {
            val issue = issue(withSteps("steps:\n  - id: s\n    actor: boss[IT]\n    do: x"), "invalid actor")
            issue.line shouldBe 10
            issue.message shouldContain "unknown role 'boss'"
        }

        @Test
        fun `actor lists must contain expressions`() {
            issue(withSteps("steps:\n  - actor: [admin, {x: 1}]\n    do: x"), "'steps[0].actor[1]' must be a single value")
            issue(withSteps("steps:\n  - actor: {x: 1}\n    do: x"), "must be an actor expression or a list of them")
        }

        @Test
        fun `step keys written without a value are errors, not silently dropped`() {
            fun step(body: String) = withSteps("steps:\n  - actor: admin\n$body")

            issue(step("    do:\n    assert:\n      - count: {selector: li, equals: 1}"), "'steps[0].do' has no value").line shouldBe 10
            issue(step("    run:\n    assert:\n      - count: {selector: li, equals: 1}"), "'steps[0].run' has no value").line shouldBe 10
            issue(step("    do: x\n    assert:"), "'steps[0].assert' has no value").line shouldBe 11
            issue(step("    do: x\n    emits:"), "'steps[0].emits' has no value").line shouldBe 11
            issue(step("    do: x\n    wait_for: ~"), "'steps[0].wait_for' has no value").line shouldBe 11
        }

        @Test
        fun `do and run are mutually exclusive`() {
            issue(withSteps("steps:\n  - actor: admin\n    do: x\n    run: login"), "has both do and run").line shouldBe 9
            issue(withSteps("steps:\n  - actor: admin\n    do:\n    run: login"), "has both do and run").line shouldBe 9
        }

        @Test
        fun `assertion items must be single known assertions`() {
            issue(withSteps("steps:\n  - actor: admin\n    assert:\n      - smells_good: true"), "unknown assertion 'smells_good'")
            issue(
                withSteps("steps:\n  - actor: admin\n    assert:\n      - visible_text: {text: a}\n        latency_max: {ms: 1}"),
                "must be a single assertion",
            ).line shouldBe 11
            issue(withSteps("steps:\n  - actor: admin\n    assert:\n      - count"), "must be a single assertion")
            issue(withSteps("steps:\n  - actor: admin\n    assert:\n      - visible_text:"), "needs its parameters")
        }

        @Test
        fun `assertion parameters are checked`() {
            fun assertion(text: String) = withSteps("steps:\n  - actor: admin\n    assert:\n      - $text")

            issue(assertion("only_one_succeeds: false"), "can only be true")
            issue(assertion("only_one_succeeds:"), "needs the value true")
            issue(assertion("only_one_succeeds: {a: 1}"), "unknown key 'a'").message shouldContain "allowed: request, oracle"
            issue(assertion("only_one_succeeds: [a]"), "must be a single value")
            issue(assertion("only_one_succeeds: {request: approve}"), "must be \"<METHOD> <path regex>\"")
                .message shouldContain "was 'approve'"
            issue(assertion("only_one_succeeds: {request: }"), "'steps[0].assert[0].only_one_succeeds.request' has no value")
            issue(assertion("only_one_succeeds: {request: [POST, /x]}"), "must be a single value")
            issue(assertion("only_one_succeeds: {oracle: {field: status}}"), "missing required key 'path'")
            issue(assertion("only_one_succeeds: {oracle: {path: /x, contains: a}}"), "unknown key 'contains'")
            issue(assertion("only_one_succeeds: {oracle: /test/x}"), "must be a map with keys path, field, equals")
            issue(assertion("not_visible: {text: a, selector: b}"), "needs exactly one of text or selector")
            issue(assertion("not_visible: {}"), "needs exactly one of text or selector")
            issue(assertion("http_status: {path: /x, equals: forbidden}"), "must be an integer, was 'forbidden'")
            issue(assertion("latency_max: {ms: 1.5}"), "'steps[0].assert[0].latency_max.ms' must be an integer")
            issue(assertion("oracle: {field: id}"), "missing required key 'path'")
            issue(assertion("oracle: {path: /x, equals: [a]}"), "must be a single value")
        }

        @Test
        fun `id sources need exactly one kind`() {
            fun source(text: String) = withSteps("target_profile:\n  id_sources:\n    e: $text\nsteps: []")

            issue(source("{url_regex: a, agent: true}"), "needs exactly one of url_regex, oracle, dom, agent, found 2").line shouldBe 10
            issue(source("{}"), "found 0")
            issue(source("{agent: false}"), "can only be true")
            issue(source("{agent: ~}"), "'target_profile.id_sources.e.agent' needs the value true")
            issue(source("~"), "'target_profile.id_sources.e' needs an id source")
            issue(source("{oracle: {path: /x}}"), "missing required key 'field'")
            issue(source("{dom: {selector: li}}"), "missing required key 'attribute'")
            issue(source("{guess: true}"), "unknown key 'guess'")
        }

        @Test
        fun `emits id_from must be a valid id source`() {
            issue(withSteps("steps:\n  - actor: admin\n    do: x\n    emits: {event: e, id_from: {agent: maybe}}"), "must be true or false")
            issue(withSteps("steps:\n  - actor: admin\n    do: x\n    emits: {id_from: {agent: true}}"), "missing required key 'event'")
        }

        @Test
        fun `run maps need a function and plain arguments`() {
            issue(withSteps("steps:\n  - actor: admin\n    run: {args: {a: 1}}"), "missing required key 'function'")
            issue(withSteps("steps:\n  - actor: admin\n    run: {function: login, args: [a]}"), "must be a map of argument names")
            issue(
                withSteps("steps:\n  - actor: admin\n    run: {function: login, args: {a: [1]}}"),
                "'steps[0].run.args.a' must be a single value",
            )
            issue(withSteps("steps:\n  - actor: admin\n    run: {function: login, args: {a: ~}}"), "'steps[0].run.args.a' has no value")
        }

        @Test
        fun `sections must have the right shape`() {
            issue(withSteps("steps: {a: 1}"), "'steps' must be a list").line shouldBe 8
            issue(withSteps("steps:\n  - ~"), "is an empty step")
            issue(withSteps("steps:\n  - just text"), "'steps[0]' must be a map")
            issue(withSteps("target_profile: {paths: [a]}\nsteps: []"), "'target_profile.paths' must be a map")
            issue(withSteps("target_profile: {selectors: {a: [b]}}\nsteps: []"), "'target_profile.selectors.a' must be a single value")
            issue("- campaign", "the campaign file must be a map")
        }

        @Test
        fun `empty names in lists are reported`() {
            issue(header.replace("departments: [IT]", "departments: [IT, ~]") + "\nsteps: []", "'campaign.departments[1]' is empty")
        }

        @Test
        fun `all mapping problems are reported together`() {
            issues(withSteps("steps:\n  - actor: boss\n    run: {}\n    parallel: maybe\n  - foo: 1")) shouldHaveSize 5
        }

        @Test
        fun `a YAML syntax error carries its line`() {
            val issue = issues("campaign:\n  testers: 3\n  roles: {admin: 1\nsteps: []").single()
            issue.message shouldContain "YAML syntax error"
            issue.line shouldBe 4
        }

        @Test
        fun `the documented bracketed actor list loads with its items quoted`() {
            val campaign = load(withSteps("steps:\n  - actor: [employee[IT], employee[dept=IT, n=1]]   # race\n    do: x"))
            campaign.steps
                .single()
                .actors.selectors shouldContainExactly
                listOf(ActorSelector(Role.EMPLOYEE, department = "IT"), ActorSelector(Role.EMPLOYEE, department = "IT", nth = 1))
            campaign.steps.single().line shouldBe 9
        }

        @Test
        fun `the actor list repair never rewrites text outside a step actor`() {
            val yaml = withSteps("steps:\n  - actor: [employee[IT], admin]\n    do: |\n      actor: [a[b]]")
            val issue = issues(yaml).single()
            issue.message shouldContain "YAML syntax error"
            issue.line shouldBe 9
        }

        @Test
        fun `a duplicate key is a syntax error on the duplicate`() {
            val issue = issues(header.replace("  seed: 7", "  seed: 7\n  seed: 8") + "\nsteps: []").single()
            issue.message shouldContain "seed"
            issue.line shouldBe 5
        }

        @Test
        fun `anchors and aliases are refused`() {
            issues(withSteps("steps:\n  - &a {actor: admin, do: x}\n  - *a")).single().message shouldContain "YAML syntax error"
        }

        @Test
        fun `an empty file is an error`() {
            issues("").single().message shouldContain "empty"
            issues("# only a comment").single().message shouldContain "empty"
            issues("~").single().message shouldContain "empty"
        }

        @Test
        fun `a missing file is an error without a line`() {
            val issue = shouldThrow<CampaignValidationException> { YamlCampaignSource().load(dir.resolve("nope.yaml")) }.issues.single()
            issue.line shouldBe null
            issue.message shouldContain "does not exist"
        }

        @Test
        fun `a directory is not a campaign file`() {
            shouldThrow<CampaignValidationException> { YamlCampaignSource().load(dir) }.issues.single().message shouldContain
                "cannot read campaign file"
        }

        @Test
        fun `a file that is not UTF-8 is an error`() {
            val file = dir.resolve("latin1.yaml")
            Files.write(file, byteArrayOf('a'.code.toByte(), 0xFF.toByte(), 0xFE.toByte()))
            shouldThrow<CampaignValidationException> { YamlCampaignSource().load(file) }.issues.single().message shouldContain
                "not valid UTF-8"
        }
    }
}
