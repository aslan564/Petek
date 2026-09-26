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

package az.petek.explorer.domain

import az.petek.explorer.support.Models
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class SiteModelDiffTest {
    @Test
    fun `identical models have an empty diff even when the LLM worded purposes and names differently`() {
        val before = Models.kadro(version = 1)
        val after =
            Models.kadro(version = 2).let { model ->
                model.copy(
                    pages = model.pages.map { it.copy(purpose = "reworded", loadMs = 999, linkCount = 42) },
                    actions =
                        model.actions.map {
                            if (it.id ==
                                "ticket-reject"
                            ) {
                                it.copy(name = "Imtina", provenance = Provenance.INFERRED)
                            } else {
                                it
                            }
                        },
                )
            }

        val diff = SiteModelDiff.between(before, after)

        diff.isEmpty shouldBe true
        diff.fromVersion shouldBe 1
        diff.toVersion shouldBe 2
    }

    @Test
    fun `added and removed pages and actions are listed`() {
        val before = Models.kadro(1)
        val after =
            Models.kadro(2).let { model ->
                model.copy(
                    pages =
                        model.pages.filter { it.urlPattern != "/announcements" } +
                            Models.page(
                                "/reports",
                                reachableBy = setOf("admin"),
                            ),
                    actions =
                        model.actions.filter { it.id != "announcement-submit" } +
                            Models.action("report-export", ActionKind.OTHER, "/reports", allowed = setOf("admin")),
                )
            }

        val diff = SiteModelDiff.between(before, after)

        diff.addedPages.map { it.urlPattern } shouldContainExactly listOf("/reports")
        diff.removedPages.map { it.urlPattern } shouldContainExactly listOf("/announcements")
        diff.addedActions.map { it.id } shouldContainExactly listOf("report-export")
        diff.removedActions.map { it.id } shouldContainExactly listOf("announcement-submit")
        diff.changedPages.shouldBeEmpty()
        diff.changedActions.shouldBeEmpty()
    }

    @Test
    fun `changed forms show added, removed and changed fields`() {
        val before = Models.kadro(1)
        val tickets = before.pageByPattern("/tickets")!!
        val form = tickets.forms.single()
        val changedForm =
            form.copy(
                fields =
                    listOf(
                        form.fields[0].copy(required = false, label = "Mövzu"),
                        form.fields[2].copy(options = listOf("IT", "HR", "Satış")),
                        Models.field("priority", "select"),
                    ),
            )
        val after =
            before.copy(
                version = 2,
                pages =
                    before.pages.map {
                        if (it.id ==
                            tickets.id
                        ) {
                            it.copy(forms = listOf(changedForm))
                        } else {
                            it
                        }
                    },
            )

        val change = SiteModelDiff.between(before, after).changedPages.single()

        change.urlPattern shouldBe "/tickets"
        val formChange = change.changedForms.single()
        formChange.addedFields.map { it.name } shouldContainExactly listOf("priority")
        formChange.removedFields.map { it.name } shouldContainExactly listOf("description")
        formChange.changedFields.map { it.key } shouldContainExactly listOf("title", "department")
        formChange.changedFields
            .first()
            .changes
            .map { it.toString() } shouldContainExactly
            listOf("label: Title -> Mövzu", "required: true -> false")
        formChange.changedFields
            .last()
            .changes
            .single()
            .toString() shouldBe "options: [IT, HR] -> [IT, HR, Satış]"
    }

    @Test
    fun `action changes compare kinds, selectors and roles over the roles both explorations used`() {
        val before = Models.kadro(1)
        val after =
            Models.kadro(2).let { model ->
                model.copy(
                    roles = model.roles.filter { it.name != "admin" },
                    actions =
                        model.actions.map {
                            when (it.id) {
                                "ticket-approve" -> {
                                    it.copy(
                                        allowedRoles = setOf("manager", "employee"),
                                        forbiddenRoles = emptySet(),
                                        selector = "#approve",
                                    )
                                }

                                "announcement-submit" -> {
                                    it.copy(allowedRoles = emptySet(), forbiddenRoles = setOf("manager", "employee"))
                                }

                                "ticket-submit" -> {
                                    it.copy(triggersRealtime = null)
                                }

                                else -> {
                                    it
                                }
                            }
                        },
                )
            }

        val diff = SiteModelDiff.between(before, after)

        val approve = diff.changedActions.single { it.actionId == "ticket-approve" }
        approve.changes.map { it.field } shouldContainExactly listOf("selector", "allowedRoles", "forbiddenRoles")
        approve.changes.first { it.field == "allowedRoles" }.after shouldBe "[employee, manager]"
        diff.changedActions.map { it.actionId } shouldContainExactly listOf("ticket-approve")
    }

    @Test
    fun `a page reachable by other roles and with new test ids is a changed page`() {
        val before = Models.kadro(1)
        val after =
            before.copy(
                version = 2,
                pages =
                    before.pages.map {
                        if (it.urlPattern ==
                            "/announcements"
                        ) {
                            it.copy(reachableBy = setOf("admin"), testIds = it.testIds + "announcement-pin")
                        } else {
                            it
                        }
                    },
            )

        val change = SiteModelDiff.between(before, after).changedPages.single()

        change.changes.map { it.toString() } shouldContainExactly
            listOf(
                "reachableBy: [admin, employee, manager] -> [admin]",
                "testIds: [announcement-item] -> [announcement-item, announcement-pin]",
            )
    }
}
