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

package az.petek.dashboard.demo

import az.petek.core.ids.AgentId
import az.petek.dashboard.domain.PlanStepView
import az.petek.dashboard.domain.RunPlanView

/**
 * Plan previews of the demo scenarios, resolved the way the orchestrator would for a 30-tester team (a01 admin,
 * a02..a06 managers, a07..a30 employees; every fifth employee is in IT), or 10 for the registration scenario.
 */
object DemoPlans {
    private fun agents(range: IntRange) = range.map(AgentId::of)

    private val ADMIN = agents(1..1)
    private val MANAGERS = agents(2..6)
    private val EMPLOYEES = agents(7..30)
    private val IT = EMPLOYEES.filterIndexed { i, _ -> i % 5 == 0 }

    fun preview(
        name: String,
        yaml: String,
    ): RunPlanView =
        when (name) {
            "kadrohr-qeydiyyat" -> {
                RunPlanView(
                    null,
                    name,
                    listOf(
                        step("owner_signup", true, "admin", ADMIN, "do", "Qeydiyyatdan keç və şirkət yarat"),
                        step("join", false, "employee[*] | manager[*]", agents(2..10), "run", "register_and_login"),
                    ),
                )
            }

            else -> {
                RunPlanView(null, name, coreSteps(yaml))
            }
        }

    private fun coreSteps(yaml: String): List<PlanStepView> =
        buildList {
            add(step("owner_signup", true, "admin", ADMIN, "do", "Qeydiyyatdan keç, email kodunu təsdiqlə və şirkət yarat"))
            if ("seed_company" in yaml) add(step("seed", true, "admin", ADMIN, "run", "seed_company"))
            add(step("join", true, "employee[*] | manager[*]", MANAGERS + EMPLOYEES, "run", "register_and_login"))
            add(step("announce", false, "admin", ADMIN, "do", "Elan yarat: 'Sabah 10:00 ümumi iclas'", emits = "announcement_created"))
            add(
                step(
                    "read_announce",
                    false,
                    "employee[*]",
                    EMPLOYEES,
                    "do",
                    "Bildirişləri aç və yeni elanı oxu",
                    waitFor = "announcement_created",
                    assertions = listOf("visible_text 'Sabah 10:00 ümumi iclas' within 5s"),
                ),
            )
            val ticket = "id: ticket\n" in yaml
            if (ticket) {
                add(step("ticket", false, "employee[dept=IT, n=1]", IT.take(1), "do", ticketText(yaml), emits = "ticket_created"))
            }
            if ("ticket_flow" in yaml) {
                add(
                    step(
                        "ticket_flow",
                        false,
                        "manager[IT]",
                        MANAGERS.take(1),
                        "do",
                        "Ticketi in-progress et, sonra HR menecerinə assign et",
                        waitFor = "ticket_created",
                    ),
                )
            }
            if ("id: race" in yaml) {
                add(
                    step(
                        "race",
                        false,
                        "[manager[IT], manager[HR]]",
                        MANAGERS.take(2),
                        "do",
                        "Eyni ticketi approve et",
                        parallel = true,
                        assertions = listOf("only_one_succeeds"),
                    ),
                )
            }
            if ("id: forbidden" in yaml) {
                add(
                    step(
                        "forbidden",
                        false,
                        "employee[dept=IT, n=2]",
                        IT.drop(1).take(2),
                        "do",
                        "Ticketi approve etməyə çalış",
                        assertions =
                            listOf(
                                "not_visible [data-testid=\"ticket-approve\"]",
                                "http_status POST /api/tickets/{last_id}/approve = 403",
                            ),
                    ),
                )
            }
        }

    private fun ticketText(yaml: String): String =
        if (yaml.contains("Müraciət yarat")) {
            "'Müraciət yarat' ilə IT-yə müraciət yaz: 'Noutbuk işləmir'"
        } else {
            "IT departamentinə ticket yaz: 'Noutbuk işləmir'"
        }

    private fun step(
        id: String,
        setup: Boolean,
        actors: String,
        agents: List<AgentId>,
        kind: String,
        action: String,
        emits: String? = null,
        waitFor: String? = null,
        parallel: Boolean = false,
        assertions: List<String> = emptyList(),
    ) = PlanStepView(id, setup, actors, agents, kind, action, emits, waitFor, parallel, assertions)
}
