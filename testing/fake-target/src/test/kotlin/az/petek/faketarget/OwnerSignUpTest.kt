package az.petek.faketarget

import az.petek.faketarget.model.UserRole
import az.petek.faketarget.support.FakeTargetFixture
import az.petek.faketarget.support.string
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldMatch
import io.kotest.matchers.string.shouldStartWith
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

class OwnerSignUpTest {
    private val fake = FakeTargetFixture()

    @AfterEach
    fun tearDown() = fake.close()

    @Test
    fun `owner signs up, verifies e-mail and phone and lands on the home page with a session cookie`() =
        runBlocking<Unit> {
            val browser = fake.browser()
            val register =
                browser.submit(
                    "/register",
                    "name" to "Əli Kərimov",
                    "email" to "Eli.Owner@Test.KadroHR.com",
                    "phone" to "+994 50 123-45-67",
                    "password" to "owner-secret-1",
                    "company" to "Pətək Test MMC",
                )
            register.status shouldBe 303
            register.location shouldBe "/verify?email=eli.owner%40test.kadrohr.com"

            val verifyPage = browser.get(register.location!!)
            verifyPage.has("verify-code") shouldBe true
            verifyPage.has("verify-submit") shouldBe true

            val mail = fake.mailsTo("eli.owner@test.kadrohr.com").single()
            mail.string("Subject") shouldBe "Təsdiq kodu"
            val code = fake.verificationCode("eli.owner@test.kadrohr.com")
            fake.message(mail.string("ID")).string("Text") shouldBe "Sizin təsdiq kodunuz: $code"

            val afterEmail = browser.submit("/verify", "email" to "eli.owner@test.kadrohr.com", "code" to code)
            afterEmail.location shouldBe "/verify/phone?email=eli.owner%40test.kadrohr.com"
            afterEmail.setCookies shouldBe emptyList()

            val phonePage = browser.get(afterEmail.location!!)
            phonePage.has("verify-phone-code") shouldBe true
            phonePage.has("verify-phone-submit") shouldBe true
            val otp = fake.otp("+994501234567")

            val afterPhone = browser.submit("/verify/phone", "email" to "eli.owner@test.kadrohr.com", "code" to otp)
            afterPhone.status shouldBe 303
            afterPhone.location shouldBe "/"
            val cookie = afterPhone.setCookies.single()
            cookie shouldMatch Regex("fake_session=[A-Za-z0-9]{43}; Path=/; HttpOnly; SameSite=Lax")

            val home = browser.get("/")
            home.status shouldBe 200
            home.text("current-user-name") shouldBe "Əli Kərimov"
            home.text("current-user-role") shouldBe "admin"
            listOf("logout", "nav-home", "nav-announcements", "nav-tickets", "nav-company", "notification-bell", "notification-list")
                .forEach { home.has(it) shouldBe true }
            home.text("notification-count") shouldBe "0"
            home.body shouldContain "new EventSource('/events'"

            val company = fake.companyOf("eli.owner@test.kadrohr.com")
            company.string("name") shouldBe "Pətək Test MMC"
            company.string("code") shouldMatch Regex("PTK-\\d{4}")
            company.string("is_test") shouldBe "true"
            val owner = fake.server.store.user("eli.owner@test.kadrohr.com")!!
            owner.role shouldBe UserRole.ADMIN
            owner.phone shouldBe "+994501234567"
            owner.emailVerified shouldBe true
            owner.phoneVerified shouldBe true
        }

    @Test
    fun `company page shows the company name and join code to the admin only`() =
        runBlocking<Unit> {
            val team = fake.team()
            val page = team.admin.browser.get("/company")
            page.status shouldBe 200
            page.text("company-name") shouldBe "Pətək Test MMC"
            page.text("company-code") shouldBe team.code
            page.count("company-member") shouldBe 6
            page.texts("company-department") shouldContainExactly listOf("IT", "HR")

            val employeeView = team.itEmployee.browser.get("/company")
            employeeView.status shouldBe 403
            employeeView.has("company-code") shouldBe false
            team.itEmployee.browser
                .get("/")
                .has("nav-company") shouldBe false
        }

    @Test
    fun `an owner outside the test mail domain creates a non-test company whose data the test API does not serve`() =
        runBlocking<Unit> {
            val browser = fake.browser()
            browser.submit(
                "/register",
                "name" to "Real Owner",
                "email" to "real.owner@example.com",
                "phone" to "+994500000077",
                "password" to "owner-secret-1",
                "company" to "Real LLC",
            )
            browser.submit("/verify", "email" to "real.owner@example.com", "code" to fake.verificationCode("real.owner@example.com"))
            fake.companyOf("real.owner@example.com").string("is_test") shouldBe "false"
            val otp = fake.testGet("/test/otp/+994500000077")
            otp.status shouldBe 403
            otp.json().string("error") shouldBe "not_a_test_company"
        }

    @Test
    fun `without the phone step the e-mail code alone opens the session`() =
        runBlocking<Unit> {
            FakeTargetFixture(FakeTargetConfig(requirePhoneOtp = false)).use { noPhone ->
                val browser = noPhone.browser()
                browser.submit(
                    "/register",
                    "name" to "Vəli",
                    "email" to "v@test.kadrohr.com",
                    "phone" to "+994500000001",
                    "password" to "owner-secret-1",
                    "company" to "X",
                )
                val verified =
                    browser.submit(
                        "/verify",
                        "email" to "v@test.kadrohr.com",
                        "code" to noPhone.verificationCode("v@test.kadrohr.com"),
                    )
                verified.location shouldBe "/"
                verified.setCookies.single() shouldStartWith "fake_session="
                noPhone.testGet("/test/otp/+994500000001").status shouldBe 404
            }
        }

    @Test
    fun `a wrong e-mail code is refused with verify-error and the right one still works`() =
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
            val code = fake.verificationCode("v@test.kadrohr.com")
            val wrong = browser.submit("/verify", "email" to "v@test.kadrohr.com", "code" to if (code == "111111") "222222" else "111111")
            wrong.status shouldBe 200
            wrong.text("verify-error") shouldBe "Kod yanlışdır."
            browser.submit("/verify", "email" to "v@test.kadrohr.com", "code" to code).location shouldStartWith "/verify/phone"
        }

    @Test
    fun `five wrong codes burn the code until a new one is requested`() =
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
            val first = fake.verificationCode("v@test.kadrohr.com")
            val wrongCode = if (first == "999999") "999998" else "999999"
            repeat(4) { browser.submit("/verify", "email" to "v@test.kadrohr.com", "code" to wrongCode) }
            browser.submit("/verify", "email" to "v@test.kadrohr.com", "code" to wrongCode).text("verify-error") shouldContain "Çox sayda"
            browser.submit("/verify", "email" to "v@test.kadrohr.com", "code" to first).text("verify-error") shouldContain "Çox sayda"

            val resent = browser.submit("/verify/resend", "email" to "v@test.kadrohr.com")
            resent.text("verify-info") shouldBe "Yeni kod göndərildi."
            fake.mailsTo("v@test.kadrohr.com").size shouldBe 2
            val second = fake.verificationCode("v@test.kadrohr.com")
            browser.submit("/verify", "email" to "v@test.kadrohr.com", "code" to second).location shouldStartWith "/verify/phone"
        }

    @Test
    fun `a wrong phone code is refused and a resent one is published for the test API`() =
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
            browser.submit("/verify", "email" to "v@test.kadrohr.com", "code" to fake.verificationCode("v@test.kadrohr.com"))
            val otp = fake.otp("+994500000001")
            val wrong =
                browser.submit(
                    "/verify/phone",
                    "email" to "v@test.kadrohr.com",
                    "code" to if (otp == "123456") "654321" else "123456",
                )
            wrong.text("verify-phone-error") shouldBe "Kod yanlışdır."

            browser.submit("/verify/phone/resend", "email" to "v@test.kadrohr.com").text("verify-phone-info") shouldBe
                "Yeni SMS kodu göndərildi."
            val fresh = fake.otp("+994500000001")
            browser.submit("/verify/phone", "email" to "v@test.kadrohr.com", "code" to fresh).location shouldBe "/"
        }

    @Test
    fun `an e-mail can be registered only once`() =
        runBlocking<Unit> {
            fake.registerOwner(email = "dup@test.kadrohr.com")
            val again =
                fake.browser().submit(
                    "/register",
                    "name" to "Başqa",
                    "email" to "DUP@test.kadrohr.com",
                    "phone" to "+994500000099",
                    "password" to "owner-secret-1",
                    "company" to "Y",
                )
            again.status shouldBe 200
            again.text("register-error") shouldBe "Bu e-poçt artıq qeydiyyatdan keçib. Daxil olun."
            fake.server.store
                .companies()
                .size shouldBe 1
        }

    @Test
    fun `sign-up fields are validated and the form keeps what was typed`() =
        runBlocking<Unit> {
            val browser = fake.browser()

            fun fields(
                phone: String = "+994500000001",
                password: String = "owner-secret-1",
                email: String = "v@test.kadrohr.com",
                name: String = "Vəli",
            ) = arrayOf("name" to name, "email" to email, "phone" to phone, "password" to password, "company" to "Firma")

            browser.submit("/register", *fields(name = " ")).text("register-error") shouldBe "Ad və soyadı daxil edin."
            browser.submit("/register", *fields(email = "not-an-email")).text("register-error") shouldBe "Düzgün e-poçt ünvanı daxil edin."
            browser.submit("/register", *fields(phone = "12")).text("register-error") shouldContain "+994501234567"
            val short = browser.submit("/register", *fields(password = "short"))
            short.text("register-error") shouldBe "Parol ən azı 8 simvol olmalıdır."
            short.attribute("register-email", "value") shouldBe "v@test.kadrohr.com"
            short.attribute("register-company", "value") shouldBe "Firma"
            short.attribute("register-password", "value") shouldBe null
            fake.server.store
                .users()
                .size shouldBe 0
        }

    @Test
    fun `login with a wrong password shows login-error and the right one opens a session`() =
        runBlocking<Unit> {
            val owner = fake.registerOwner()
            val browser = fake.browser()
            val wrong = browser.submit("/login", "email" to owner.email, "password" to "nope-nope-nope")
            wrong.status shouldBe 200
            wrong.text("login-error") shouldBe "E-poçt və ya parol yanlışdır."
            wrong.setCookies shouldBe emptyList()
            browser.submit("/login", "email" to "nobody@test.kadrohr.com", "password" to "x").text("login-error") shouldBe
                "E-poçt və ya parol yanlışdır."

            val ok = browser.submit("/login", "email" to owner.email.uppercase(), "password" to owner.password)
            ok.location shouldBe "/"
            browser.get("/").text("current-user-name") shouldBe owner.name
        }

    @Test
    fun `login resumes an unfinished verification instead of opening a session`() =
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
            val login = fake.browser().submit("/login", "email" to "v@test.kadrohr.com", "password" to "owner-secret-1")
            login.location shouldBe "/verify?email=v%40test.kadrohr.com"
            login.setCookies shouldBe emptyList()
            fake.mailsTo("v@test.kadrohr.com").size shouldBe 2
        }

    @Test
    fun `pages need a session and send the visitor back after login`() =
        runBlocking<Unit> {
            val owner = fake.registerOwner()
            val browser = fake.browser()
            val redirect = browser.get("/tickets")
            redirect.status shouldBe 303
            redirect.location shouldBe "/login?next=%2Ftickets"
            browser.get(redirect.location!!).attribute("login-email", "id") shouldBe "login-email"
            val login = browser.submit("/login", "email" to owner.email, "password" to owner.password, "next" to "/tickets")
            login.location shouldBe "/tickets"
            browser.submit("/login", "email" to owner.email, "password" to owner.password, "next" to "//evil.example").location shouldBe "/"
        }

    @Test
    fun `logout ends the session`() =
        runBlocking<Unit> {
            val owner = fake.registerOwner()
            val logout = owner.browser.submit("/logout")
            logout.location shouldBe "/login"
            logout.setCookies.single() shouldContain "Max-Age=0"
            owner.browser.get("/").status shouldBe 303
            fake.server.store
                .user(owner.email) shouldNotBe null
        }

    @Test
    fun `every form control has a real label`() =
        runBlocking<Unit> {
            val register = fake.browser().get("/register")
            listOf("register-name", "register-email", "register-phone", "register-password", "register-company").forEach { id ->
                register.body shouldContain """<label for="$id">"""
                register.attribute(id, "id") shouldBe id
            }
        }
}
