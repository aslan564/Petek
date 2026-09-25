package az.petek.faketarget

import az.petek.faketarget.support.FakeTargetFixture
import az.petek.faketarget.support.Invite
import az.petek.faketarget.support.string
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldMatch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

class TestApiTest {
    private val fake = FakeTargetFixture(FakeTargetConfig(testToken = "s3cret-token"))

    @AfterEach
    fun tearDown() = fake.close()

    @Test
    fun `every test endpoint answers 401 without the right token`() =
        runBlocking<Unit> {
            val owner = fake.registerOwner()
            val companyId = fake.companyOf(owner.email).string("id")
            val gets =
                listOf(
                    "/test/otp/${owner.phone}",
                    "/test/companies?owner=${owner.email}",
                    "/test/companies/$companyId",
                    "/test/announcements/latest?by=${owner.email}",
                    "/test/announcements/a1",
                    "/test/announcements/a1/receipts",
                    "/test/tickets/latest?by=${owner.email}",
                    "/test/tickets/t1",
                    "/test/notifications?user=${owner.email}",
                    "/test/does-not-exist",
                )
            for (token in listOf(null, "", "wrong-token", "S3CRET-TOKEN")) {
                gets.forEach { path ->
                    val page = fake.testGet(path, token)
                    page.status shouldBe 401
                    page.json().string("error") shouldBe "invalid_test_token"
                }
                fake.testPost("/test/companies/seed", """{"company_id":"$companyId"}""", token).status shouldBe 401
                fake.testDelete("/test/companies/$companyId", token).status shouldBe 401
            }
            fake.server.store
                .company(companyId) shouldNotBe null
            fake.testGet("/test/does-not-exist").status shouldBe 404
        }

    @Test
    fun `companies are found by owner and by id with the contract's shape`() =
        runBlocking<Unit> {
            val owner = fake.registerOwner()
            val byOwner = fake.testGet("/test/companies?owner=${owner.email.uppercase()}")
            byOwner.status shouldBe 200
            val company = byOwner.json()
            company.keys shouldBe setOf("id", "name", "code", "is_test", "owner", "created_at")
            company.string("id") shouldMatch Regex("c\\d+")
            company.string("code") shouldMatch Regex("PTK-\\d{4}")
            fake.testGet("/test/companies/${company.string("id")}").json() shouldBe company
            fake.testGet("/test/companies?owner=nobody@test.kadrohr.com").status shouldBe 404
            fake.testGet("/test/companies/c999").status shouldBe 404
            fake.testGet("/test/companies").status shouldBe 400
        }

    @Test
    fun `the phone OTP is served for the latest code and 404 when there is none`() =
        runBlocking<Unit> {
            val browser = fake.browser()
            browser.submit(
                "/register",
                "name" to "Vəli",
                "email" to "v@test.kadrohr.com",
                "phone" to "+994500000001",
                "password" to "owner-secret-1",
                "company" to "X",
            )
            fake.testGet("/test/otp/+994500000001").status shouldBe 404
            browser.submit("/verify", "email" to "v@test.kadrohr.com", "code" to fake.verificationCode("v@test.kadrohr.com"))
            val otp = fake.testGet("/test/otp/+994500000001")
            otp.status shouldBe 200
            otp.json().keys shouldBe setOf("phone", "code")
            otp.json().string("phone") shouldBe "+994500000001"
            otp.json().string("code") shouldMatch Regex("\\d{6}")
            fake.testGet("/test/otp/%2B994500000001").json() shouldBe otp.json()
            fake.testGet("/test/otp/+994509999999").status shouldBe 404
        }

    @Test
    fun `seeding is idempotent per department name and invite e-mail`() =
        runBlocking<Unit> {
            val owner = fake.registerOwner()
            val companyId = fake.companyOf(owner.email).string("id")
            val invites =
                listOf(
                    Invite("a@test.kadrohr.com", "A", "manager", "IT"),
                    Invite("b@test.kadrohr.com", "B", "employee", "Satış"),
                )
            val first = fake.seed(companyId, listOf("IT", "HR"), invites)
            first.string("company_id") shouldBe companyId
            first.string("code") shouldBe fake.companyOf(owner.email).string("code")
            first["departments"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content } shouldBe
                mapOf("IT" to "d1", "HR" to "d2", "Satış" to "d3")
            val links = first["invites"]!!.jsonArray.map { it.jsonObject.string("email") to it.jsonObject.string("link") }
            links.map { it.first } shouldContainExactly listOf("a@test.kadrohr.com", "b@test.kadrohr.com")

            val second = fake.seed(companyId, listOf("HR", "IT", "Maliyyə"), invites.reversed())
            second["departments"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content } shouldBe
                mapOf("IT" to "d1", "HR" to "d2", "Satış" to "d3", "Maliyyə" to "d4")
            second["invites"]!!.jsonArray.map { it.jsonObject.string("email") to it.jsonObject.string("link") } shouldBe links.reversed()
            fake.mailsTo("a@test.kadrohr.com") shouldHaveSize 1
            fake.server.store
                .invitations() shouldHaveSize 2
        }

    @Test
    fun `seeding rejects bad input and non-test companies`() =
        runBlocking<Unit> {
            val owner = fake.registerOwner()
            val companyId = fake.companyOf(owner.email).string("id")
            fake.testPost("/test/companies/seed", "{not json").status shouldBe 400
            fake.testPost("/test/companies/seed", """{"departments":["IT"]}""").status shouldBe 400
            fake.testPost("/test/companies/seed", """{"company_id":"c999"}""").status shouldBe 404
            val badRole =
                fake.testPost(
                    "/test/companies/seed",
                    """{"company_id":"$companyId","invites":[{"email":"x@test.kadrohr.com","role":"boss"}]}""",
                )
            badRole.status shouldBe 400
            badRole.json().string("error") shouldBe "invalid_role"
            fake.testPost("/test/companies/seed", """{"company_id":"$companyId","invites":[{"email":"nope"}]}""").status shouldBe 400
            fake.testPost("/test/companies/seed", """{"company_id":"$companyId","departments":[" "]}""").status shouldBe 400
            fake.server.store
                .invitations()
                .shouldBeEmpty()

            val real = fake.browser()
            real.submit(
                "/register",
                "name" to "R",
                "email" to "real@example.com",
                "phone" to "+994500000070",
                "password" to "owner-secret-1",
                "company" to "Real",
            )
            val realId = fake.companyOf("real@example.com").string("id")
            fake.testPost("/test/companies/seed", """{"company_id":"$realId","departments":["IT"]}""").status shouldBe 403
        }

    @Test
    fun `deleting a test company removes everything in it and ends its sessions`() =
        runBlocking<Unit> {
            val team = fake.team()
            team.admin.browser.submit("/announcements", "title" to "Elan", "body" to "")
            team.itEmployee.browser.submit("/tickets", "title" to "T", "description" to "", "department" to "IT")
            val other = fake.registerOwner(email = "other@test.kadrohr.com", company = "Qalan MMC")

            val deleted = fake.testDelete("/test/companies/${team.companyId}")
            deleted.status shouldBe 204
            deleted.body shouldBe ""

            val store = fake.server.store
            store.companies().map { it.name } shouldContainExactly listOf("Qalan MMC")
            store.users().map { it.email } shouldContainExactly listOf(other.email)
            store.announcements().shouldBeEmpty()
            store.tickets().shouldBeEmpty()
            store.notifications().shouldBeEmpty()
            store.invitations().shouldBeEmpty()
            store.departments(team.companyId).shouldBeEmpty()
            team.itEmployee.browser
                .get("/")
                .status shouldBe 303
            fake.testGet("/test/companies/${team.companyId}").status shouldBe 404
            fake.testDelete("/test/companies/${team.companyId}").status shouldBe 404
            other.browser.get("/").status shouldBe 200
        }

    @Test
    fun `deleting a company that is not a test company is refused with 403`() =
        runBlocking<Unit> {
            val real = fake.browser()
            real.submit(
                "/register",
                "name" to "R",
                "email" to "real@example.com",
                "phone" to "+994500000070",
                "password" to "owner-secret-1",
                "company" to "Real",
            )
            val id = fake.companyOf("real@example.com").string("id")
            val refused = fake.testDelete("/test/companies/$id")
            refused.status shouldBe 403
            refused.json().string("error") shouldBe "not_a_test_company"
            fake.server.store
                .company(id) shouldNotBe null
        }

    @Test
    fun `latest endpoints and lookups answer 404 for unknown people and objects`() =
        runBlocking<Unit> {
            val owner = fake.registerOwner()
            fake.testGet("/test/announcements/latest?by=${owner.email}").status shouldBe 404
            fake.testGet("/test/announcements/latest?by=nobody@test.kadrohr.com").status shouldBe 404
            fake.testGet("/test/announcements/a404").status shouldBe 404
            fake.testGet("/test/announcements/a404/receipts").status shouldBe 404
            fake.testGet("/test/tickets/latest?by=${owner.email}").status shouldBe 404
            fake.testGet("/test/tickets/t404").status shouldBe 404
            fake.testGet("/test/notifications?user=nobody@test.kadrohr.com").status shouldBe 404
            fake
                .testGet("/test/notifications?user=${owner.email}")
                .json()["notifications"]!!
                .jsonArray
                .shouldBeEmpty()
        }

    @Test
    fun `latest returns the newest object of that author with the contract's shapes`() =
        runBlocking<Unit> {
            val team = fake.team()
            listOf("Birinci", "İkinci").forEach { team.admin.browser.submit("/announcements", "title" to it, "body" to "Mətn") }
            val announcement = fake.testGet("/test/announcements/latest?by=${team.admin.email}").json()
            announcement.keys shouldBe setOf("id", "title", "body", "status", "created_at", "created_by", "audience")
            announcement.string("title") shouldBe "İkinci"
            fake.testGet("/test/announcements/${announcement.string("id")}").json() shouldBe announcement
            fake.testGet("/test/announcements/${announcement.string("id")}/receipts").json().keys shouldBe
                setOf("announcement_id", "receipts")

            listOf("Bir", "İki").forEach {
                team.hrEmployee.browser.submit("/tickets", "title" to it, "description" to "", "department" to "HR")
            }
            val ticket = fake.testGet("/test/tickets/latest?by=${team.hrEmployee.email}").json()
            ticket.keys shouldBe
                setOf("id", "title", "description", "department", "status", "assignee", "created_by", "created_at", "history")
            ticket.string("title") shouldBe "İki"
            ticket.string("department") shouldBe "HR"

            val notification =
                fake
                    .testGet("/test/notifications?user=${team.hrManager.email}")
                    .json()["notifications"]!!
                    .jsonArray
                    .first()
                    .jsonObject
            notification.keys shouldBe setOf("id", "type", "object_id", "text", "link", "created_at", "read_at")
            notification.string("type") shouldBe "ticket_created"
            notification.string("object_id") shouldBe ticket.string("id")
        }
}
