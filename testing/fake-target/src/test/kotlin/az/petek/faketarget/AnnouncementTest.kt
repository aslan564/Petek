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

package az.petek.faketarget

import az.petek.faketarget.model.User
import az.petek.faketarget.model.UserRole
import az.petek.faketarget.service.AnnouncementService
import az.petek.faketarget.support.FakeTargetFixture
import az.petek.faketarget.support.LiveStream
import az.petek.faketarget.support.Member
import az.petek.faketarget.support.Team
import az.petek.faketarget.support.string
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import kotlin.time.Duration.Companion.seconds

class AnnouncementTest {
    private val fake = FakeTargetFixture()

    @AfterEach
    fun tearDown() = fake.close()

    private suspend fun announce(
        team: Team,
        title: String = "Sabah 10:00 ümumi iclas",
        body: String = "Hamı iclas zalına",
    ): String {
        val created = team.admin.browser.submit("/announcements", "title" to title, "body" to body)
        created.status shouldBe 303
        return created.location!!.substringAfterLast('/')
    }

    private suspend fun receipts(id: String): List<String> =
        fake
            .testGet("/test/announcements/$id/receipts")
            .json()["receipts"]!!
            .jsonArray
            .map { it.jsonObject.string("email") }

    private suspend fun notificationsOf(member: Member): List<JsonObject> =
        fake
            .testGet("/test/notifications?user=${member.email}")
            .json()["notifications"]!!
            .jsonArray
            .map { it.jsonObject }

    @Test
    fun `only the admin sees the create form and may publish`() =
        runBlocking<Unit> {
            val team = fake.team()
            val adminView = team.admin.browser.get("/announcements")
            listOf("announcement-create", "announcement-title", "announcement-body", "announcement-submit").forEach {
                adminView.has(it) shouldBe true
            }
            val managerView = team.itManager.browser.get("/announcements")
            managerView.status shouldBe 200
            managerView.has("announcement-create") shouldBe false
            managerView.has("announcement-submit") shouldBe false

            val forbidden = team.itEmployee.browser.submit("/announcements", "title" to "Mən də", "body" to "")
            forbidden.status shouldBe 403
            forbidden.text("page-error") shouldBe "Bu əməliyyat üçün icazəniz yoxdur."
            fake.server.store
                .announcements()
                .shouldBeEmpty()
        }

    @Test
    fun `publishing fans out a notification to every other member and shows up in their panel`() =
        runBlocking<Unit> {
            val team = fake.team()
            val id = announce(team)

            val detail = team.admin.browser.get("/announcements/$id")
            detail.text("announcement-body-text") shouldBe "Hamı iclas zalına"
            detail.attribute("announcement-item", "data-id") shouldBe id

            val oracle = fake.testGet("/test/announcements/$id").json()
            oracle.string("title") shouldBe "Sabah 10:00 ümumi iclas"
            oracle.string("status") shouldBe "published"
            oracle.string("created_by") shouldBe team.admin.email
            oracle["audience"]!!.jsonArray.map { it.jsonPrimitive.content } shouldContainExactlyInAnyOrder
                (team.everyone - team.admin).map { it.email }
            fake.testGet("/test/announcements/latest?by=${team.admin.email}").json().string("id") shouldBe id

            (team.everyone - team.admin).forEach { member ->
                val notifications = notificationsOf(member)
                notifications shouldHaveSize 1
                notifications.single().string("type") shouldBe "announcement"
                notifications.single().string("object_id") shouldBe id
                notifications.single()["read_at"] shouldBe JsonNull

                val home = member.browser.get("/")
                home.text("notification-count") shouldBe "1"
                home.text("notification-item") shouldBe "Sabah 10:00 ümumi iclas"
                home.attribute("notification-item", "data-id") shouldBe id
                home.attribute("notification-list", "data-last-id") shouldBe notifications.single().string("id")
            }
            notificationsOf(team.admin).shouldBeEmpty()
        }

    @Test
    fun `reading the list, the detail page or the notification list records a receipt`() =
        runBlocking<Unit> {
            val team = fake.team()
            val id = announce(team)
            receipts(id).shouldBeEmpty()

            team.itEmployee.browser
                .get("/announcements")
                .attributes("announcement-item", "data-id") shouldContainExactly listOf(id)
            team.hrEmployee.browser
                .get("/announcements/$id")
                .text("announcement-body-text") shouldBe "Hamı iclas zalına"
            val notificationsPage = team.itManager.browser.get("/notifications")
            notificationsPage.count("notification-entry") shouldBe 1
            notificationsPage.text("notification-count") shouldBe "0"

            receipts(id) shouldContainExactlyInAnyOrder listOf(team.itEmployee.email, team.hrEmployee.email, team.itManager.email)
            notificationsOf(team.itEmployee).single()["read_at"] shouldNotBe JsonNull
            team.itEmployee.browser
                .get("/")
                .text("notification-count") shouldBe "0"

            val firstRead =
                fake
                    .testGet("/test/announcements/$id/receipts")
                    .json()["receipts"]!!
                    .jsonArray
                    .first()
                    .jsonObject
                    .string("read_at")
            team.itEmployee.browser.get("/announcements")
            fake
                .testGet("/test/announcements/$id/receipts")
                .json()["receipts"]!!
                .jsonArray
                .first()
                .jsonObject
                .string("read_at") shouldBe firstRead
            team.admin.browser.get("/announcements/$id")
            receipts(id) shouldHaveSize 3
        }

    @Test
    fun `a title is required and errors keep the typed text`() =
        runBlocking<Unit> {
            val team = fake.team()
            val page = team.admin.browser.submit("/announcements", "title" to "  ", "body" to "Mətn qalır")
            page.status shouldBe 400
            page.text("announcement-error") shouldBe "Başlığı daxil edin (ən çox 200 simvol)."
            page.text("announcement-body") shouldBe "Mətn qalır"
        }

    @Test
    fun `announcements of another company are invisible`() =
        runBlocking<Unit> {
            val team = fake.team()
            val id = announce(team)
            val stranger = fake.registerOwner(email = "other@test.kadrohr.com", company = "Başqa MMC")
            stranger.browser.get("/announcements/$id").status shouldBe 404
            stranger.browser
                .get("/announcements")
                .count("announcement-item") shouldBe 0
        }

    @Test
    fun `with a notification delay the fan-out happens later, in the background`() =
        runBlocking<Unit> {
            FakeTargetFixture(FakeTargetConfig(notificationDelay = 1.seconds)).use { slow ->
                val team = slow.team()
                val created = team.admin.browser.submit("/announcements", "title" to "Gecikən elan", "body" to "")
                val id = created.location!!.substringAfterLast('/')
                slow.server.store
                    .notifications(team.itEmployee.email)
                    .shouldBeEmpty()
                val event = LiveStream(team.itEmployee.browser).use { it.awaitNotification() }
                event.string("object_id") shouldBe id
                val notification =
                    slow.server.store
                        .notifications(team.itEmployee.email)
                        .single()
                val announcement =
                    slow.server.store
                        .announcement(id)
                        .shouldNotBeNull()
                Duration.between(announcement.createdAt, notification.createdAt) shouldBeGreaterThanOrEqualTo Duration.ofSeconds(1)
            }
        }

    @Test
    fun `the notification bug drops exactly one employee, who gets no notification and no receipt`() =
        runBlocking<Unit> {
            FakeTargetFixture(FakeTargetConfig(bugs = setOf(FakeBug.DROP_NOTIFICATION_FOR_ONE_USER))).use { buggy ->
                val team = buggy.team()
                val created = team.admin.browser.submit("/announcements", "title" to "Elan", "body" to "")
                val id = created.location!!.substringAfterLast('/')
                // Employees in e-mail order: hr.employee@ < it.employee2@ < it.employee@.
                val victim = team.hrEmployee

                val announcement =
                    buggy.server.store
                        .announcement(id)
                        .shouldNotBeNull()
                announcement.droppedRecipients shouldContainExactly setOf(victim.email)
                announcement.audience shouldHaveSize 5
                buggy.server.store
                    .notifications(victim.email)
                    .shouldBeEmpty()
                (team.everyone - team.admin - victim).forEach {
                    buggy.server.store
                        .notifications(it.email)
                        .shouldHaveSize(1)
                }

                victim.browser
                    .get("/")
                    .text("notification-count") shouldBe "0"
                victim.browser
                    .get("/announcements/$id")
                    .status shouldBe 200
                victim.browser.get("/notifications")
                team.itEmployee2.browser.get("/announcements/$id")
                buggy.server.store
                    .receipts(id)
                    .map { it.email } shouldBe listOf(team.itEmployee2.email)
            }
        }

    @Test
    fun `the dropped recipient is the first employee by e-mail, else the first recipient`() {
        fun user(
            email: String,
            role: UserRole,
        ) = User("u", "c", email, email, "+994500000000", role, null, emailVerified = true, phoneVerified = true, createdAt = Instant.EPOCH)
        val manager = user("a.manager@x.az", UserRole.MANAGER)
        val employeeB = user("b.employee@x.az", UserRole.EMPLOYEE)
        val employeeC = user("c.employee@x.az", UserRole.EMPLOYEE)
        AnnouncementService.dropVictim(listOf(employeeC, manager, employeeB)) shouldBe "b.employee@x.az"
        AnnouncementService.dropVictim(listOf(manager)) shouldBe "a.manager@x.az"
        AnnouncementService.dropVictim(emptyList()) shouldBe null
    }
}
