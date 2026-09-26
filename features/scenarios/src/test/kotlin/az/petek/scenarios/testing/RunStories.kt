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

package az.petek.scenarios.testing

import az.petek.evidence.domain.ArtifactType
import az.petek.evidence.domain.EvidenceSource
import az.petek.evidence.domain.FindingClass
import az.petek.evidence.domain.StepKind
import az.petek.evidence.domain.StepStatus
import az.petek.evidence.domain.Verdict
import az.petek.evidence.testing.InMemoryEvidence
import az.petek.scenarios.domain.RunEvidence
import az.petek.scenarios.testing.ScenarioTestKit.artifact
import az.petek.scenarios.testing.ScenarioTestKit.assertion
import az.petek.scenarios.testing.ScenarioTestKit.finding
import az.petek.scenarios.testing.ScenarioTestKit.step

/**
 * Evidence of typical situations in a run of [ScenarioTestKit.MINI_YAML], written the way the agent loop and the
 * orchestrator write it (turn steps, concluding `do:`/`run` steps with `<failure key>: <summary>` details, findings).
 * Agents: a01 admin, a02 manager, a03 and a04 employees.
 */
object RunStories {
    val EMPTY = RunEvidence(emptyList(), emptyList(), emptyList(), emptyList())

    operator fun RunEvidence.plus(other: RunEvidence) =
        RunEvidence(steps + other.steps, assertions + other.assertions, findings + other.findings, artifacts + other.artifacts)

    fun RunEvidence.into(store: InMemoryEvidence) {
        store.stepList += steps
        store.assertionList += assertions
        store.findingList += findings
        store.artifactList += artifacts
    }

    /** a03 cannot find the announcement and reports a bug (plus the failed check and the judge's finding). */
    fun problemReported(agent: String = "a03") =
        RunEvidence(
            steps =
                listOf(
                    step(
                        "stp_${agent}_1",
                        agent,
                        "read_announce",
                        StepKind.WAIT,
                        "wait_for announcement_created",
                        StepStatus.PASSED,
                        "received",
                    ),
                    step(
                        "stp_${agent}_2",
                        agent,
                        "read_announce",
                        StepKind.DO,
                        "click [3] \"Bildirişlər\"",
                        StepStatus.PASSED,
                        "Opened the notification list",
                        "The bell opens the notifications",
                        second = 2,
                    ),
                    step(
                        "stp_${agent}_3",
                        agent,
                        "read_announce",
                        StepKind.DO,
                        "report_problem bug \"Elan siyahıda yoxdur\"",
                        StepStatus.FAILED,
                        "Problem reported: bug: Elan siyahıda yoxdur | outcome: FAILED problem_reported: bug: Elan siyahıda yoxdur",
                        "The list is empty",
                        second = 4,
                    ),
                    step(
                        "stp_${agent}_4",
                        agent,
                        "read_announce",
                        StepKind.DO,
                        "do: Bildirişləri aç və yeni elanı oxu",
                        StepStatus.FAILED,
                        "problem_reported: bug: Elan siyahıda yoxdur",
                        second = 5,
                    ),
                ),
            assertions =
                listOf(
                    assertion(
                        "stp_${agent}_4",
                        agent,
                        "read_announce",
                        "visible_text",
                        Verdict.FAILED,
                        expected = "Sabah 10:00 ümumi iclas within 5s",
                        observed = null,
                        artifacts = listOf("art_${agent}_3"),
                    ),
                ),
            findings =
                listOf(
                    finding(
                        "fnd_$agent",
                        "stp_${agent}_3",
                        "read_announce",
                        agent,
                        FindingClass.AGENT_FAILURE,
                        "Agent failure: problem_reported.",
                    ),
                ),
            artifacts =
                listOf(
                    artifact("art_${agent}_1", "stp_${agent}_3"),
                    artifact("art_${agent}_2", "stp_${agent}_3", ArtifactType.A11Y),
                    artifact("art_${agent}_3", "stp_${agent}_4"),
                ),
        )

    /** The admin's announcement works, although one click missed on the way (an intermediate failure). */
    fun announcementPassed() =
        RunEvidence(
            steps =
                listOf(
                    step("stp_a01_1", "a01", "announce", StepKind.DO, "click [9]", StepStatus.FAILED, "element [9] is not on the page"),
                    step(
                        "stp_a01_2",
                        "a01",
                        "announce",
                        StepKind.DO,
                        "click [10] \"Elan yarat\"",
                        StepStatus.PASSED,
                        "Form opened",
                        second = 1,
                    ),
                    step(
                        "stp_a01_3",
                        "a01",
                        "announce",
                        StepKind.DO,
                        "do: Elan yarat: 'Sabah 10:00 ümumi iclas'",
                        StepStatus.PASSED,
                        "done",
                        second = 2,
                    ),
                    step(
                        "stp_a01_4",
                        "a01",
                        "announce",
                        StepKind.EMIT,
                        "emit announcement_created",
                        StepStatus.PASSED,
                        "id=17",
                        second = 3,
                    ),
                ),
            assertions = emptyList(),
            findings = emptyList(),
            artifacts = emptyList(),
        )

    /** a04 cannot join: the e-mail code is rejected twice (`run` sub-steps, the function's and the orchestrator's conclusions). */
    fun failedJoin(agent: String = "a04") =
        RunEvidence(
            steps =
                listOf(
                    step("stp_${agent}_j1", agent, "join", StepKind.RUN, "register_and_login: open /join", StepStatus.PASSED),
                    step(
                        "stp_${agent}_j2",
                        agent,
                        "join",
                        StepKind.RUN,
                        "register_and_login: wait for verify.code",
                        StepStatus.FAILED,
                        second = 1,
                    ),
                    step(
                        "stp_${agent}_j3",
                        agent,
                        "join",
                        StepKind.RUN,
                        "run register_and_login",
                        StepStatus.FAILED,
                        "otp_rejected: code rejected twice",
                        second = 2,
                    ),
                    step(
                        "stp_${agent}_j4",
                        agent,
                        "join",
                        StepKind.RUN,
                        "run register_and_login",
                        StepStatus.FAILED,
                        "otp_rejected: code rejected twice",
                        second = 3,
                    ),
                ),
            assertions = emptyList(),
            findings =
                listOf(
                    finding(
                        "fnd_${agent}_join",
                        "stp_${agent}_j2",
                        "join",
                        agent,
                        FindingClass.AGENT_FAILURE,
                        "Agent failure: otp_rejected.",
                    ),
                ),
            artifacts = listOf(artifact("art_${agent}_j", "stp_${agent}_j3")),
        )

    /** a04 is refused approving the ticket, as the forbidden-action test expects; [statusFails] = the 403 check failed. */
    fun expectedRefusal(
        agent: String = "a04",
        statusFails: Boolean = false,
    ) = RunEvidence(
        steps =
            listOf(
                step(
                    "stp_${agent}_f1",
                    agent,
                    "forbidden",
                    StepKind.DO,
                    "report_problem permission_denied \"Approve düyməsi yoxdur\"",
                    StepStatus.BLOCKED,
                    "Problem reported: Approve düyməsi yoxdur | outcome: BLOCKED permission_denied: Approve düyməsi yoxdur",
                ),
                step(
                    "stp_${agent}_f2",
                    agent,
                    "forbidden",
                    StepKind.DO,
                    "do: Ticketi approve etməyə çalış",
                    StepStatus.BLOCKED,
                    "permission_denied: permission_denied: Approve düyməsi yoxdur",
                    second = 1,
                ),
            ),
        assertions =
            listOf(
                assertion(
                    "stp_${agent}_f2",
                    agent,
                    "forbidden",
                    "http_status",
                    if (statusFails) Verdict.FAILED else Verdict.PASSED,
                    expected = "POST /api/tickets/17/approve -> 403",
                    observed = if (statusFails) "200" else "403",
                    source = EvidenceSource.ORACLE,
                ),
            ),
        findings =
            if (statusFails) {
                listOf(
                    finding(
                        "fnd_${agent}_403",
                        "stp_${agent}_f2",
                        "forbidden",
                        agent,
                        FindingClass.BACKEND,
                        "The target let an employee approve.",
                    ),
                )
            } else {
                emptyList()
            },
        artifacts = emptyList(),
    )

    /** a02 wins the race, a03 loses it; [won] = the `only_one_succeeds` check passed. */
    fun race(won: Boolean = true) =
        RunEvidence(
            steps =
                listOf(
                    step("stp_race_a02", "a02", "race", StepKind.DO, "do: Eyni ticketi approve et", StepStatus.PASSED, "approved"),
                    step(
                        "stp_race_a03_t",
                        "a03",
                        "race",
                        StepKind.DO,
                        "report_problem bug \"Ticket artıq təsdiqlənib\"",
                        StepStatus.FAILED,
                        "Problem reported: bug: Ticket artıq təsdiqlənib | " +
                            "outcome: FAILED problem_reported: bug: Ticket artıq təsdiqlənib",
                    ),
                    step(
                        "stp_race_a03",
                        "a03",
                        "race",
                        StepKind.DO,
                        "do: Eyni ticketi approve et",
                        StepStatus.FAILED,
                        "problem_reported: bug: Ticket artıq təsdiqlənib",
                        second = 1,
                    ),
                    step(
                        "stp_race_group",
                        null,
                        "race",
                        StepKind.SYSTEM,
                        "verify_group only_one_succeeds",
                        if (won) StepStatus.PASSED else StepStatus.FAILED,
                        "only_one_succeeds: ${if (won) "PASSED" else "FAILED"}",
                        second = 2,
                    ),
                ),
            assertions =
                listOf(
                    assertion(
                        "stp_race_group",
                        null,
                        "race",
                        "only_one_succeeds",
                        if (won) Verdict.PASSED else Verdict.FAILED,
                        expected = "exactly one winner",
                        observed = if (won) "a02 succeeded" else "no actor succeeded",
                        source = EvidenceSource.SENDER,
                    ),
                ),
            findings =
                listOf(
                    finding(
                        "fnd_race_a03",
                        "stp_race_a03_t",
                        "race",
                        "a03",
                        FindingClass.AGENT_FAILURE,
                        "Agent failure: problem_reported.",
                    ),
                ),
            artifacts = emptyList(),
        )

    /** The model could not be reached while the admin announced: an environment problem. */
    fun environmentFailure() =
        RunEvidence(
            steps =
                listOf(
                    step(
                        "stp_env_1",
                        "a01",
                        "announce",
                        StepKind.DO,
                        "do: Elan yarat: 'Sabah 10:00 ümumi iclas'",
                        StepStatus.ERROR,
                        "llm_unavailable: LLM unavailable: not logged in",
                    ),
                ),
            assertions = emptyList(),
            findings =
                listOf(
                    finding(
                        "fnd_env",
                        "stp_env_1",
                        "announce",
                        "a01",
                        FindingClass.AGENT_FAILURE,
                        "Agent failure: llm_unavailable.",
                    ),
                ),
            artifacts = emptyList(),
        )
}
