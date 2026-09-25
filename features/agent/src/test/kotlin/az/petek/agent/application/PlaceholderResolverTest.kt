package az.petek.agent.application

import az.petek.agent.application.PlaceholderResolver.Resolution
import az.petek.agent.domain.AgentVariableKeys
import az.petek.agent.domain.SharedRunState
import az.petek.agent.testing.AgentTestData
import az.petek.browser.testing.FakeBrowserSession
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

class PlaceholderResolverTest {
    private val resolver = PlaceholderResolver()
    private val identity = AgentTestData.itEmployee
    private val runtime = AgentTestData.runtime(FakeBrowserSession(), identity)

    private fun resolved(text: String) = resolver.resolve(text, runtime).shouldBeInstanceOf<Resolution.Resolved>().text

    private fun unresolved(text: String) = resolver.resolve(text, runtime).shouldBeInstanceOf<Resolution.Unresolved>().message

    @Test
    fun `self placeholders resolve from the agent's identity`() {
        resolved("{self.email}") shouldBe identity.email
        resolved("{self.password}") shouldBe identity.password.reveal()
        resolved("{self.name}") shouldBe "Cəmil Əliyev"
        resolved("{self.phone}") shouldBe identity.phone
        resolved("{self.agent_id}") shouldBe "a04"
        resolved("{self.department}") shouldBe "IT"
        resolved("{self.role}") shouldBe "employee"
    }

    @Test
    fun `variables and shared values resolve once they are set`() {
        runtime.variables[AgentVariableKeys.EMAIL_CODE] = "123456"
        runtime.variables[AgentVariableKeys.PHONE_CODE] = "482913"
        runtime.shared.put(SharedRunState.COMPANY_CODE, "PTK-4821")
        runtime.shared.put(SharedRunState.COMPANY_ID, "c1")
        resolved("{vars.email_code}") shouldBe "123456"
        resolved("{vars.phone_code}") shouldBe "482913"
        resolved("{shared.company_code}") shouldBe "PTK-4821"
        resolved("{shared.company_id}") shouldBe "c1"
    }

    @Test
    fun `several placeholders and plain text are combined`() {
        resolved("Kod: {vars.x} / {self.agent_id}".replace("{vars.x}", "{self.department}")) shouldBe "Kod: IT / a04"
        resolved("Salam, dünya!") shouldBe "Salam, dünya!"
        resolved("") shouldBe ""
    }

    @Test
    fun `braces that are not identifiers are typed literally`() {
        resolved("{1} { } {} {-x}") shouldBe "{1} { } {} {-x}"
    }

    @Test
    fun `an unknown placeholder is an error message for the model, not an exception`() {
        val message = unresolved("{self.salary}")
        message shouldContain "Unknown placeholder {self.salary}."
        message shouldContain "{self.email}"
        message shouldContain "{self.password}"
        unresolved("{email_code}") shouldContain "Unknown placeholder {email_code}."
        unresolved("{foo.bar}") shouldContain "Unknown placeholder {foo.bar}."
        unresolved("{shared.invite_link}") shouldContain "Unknown placeholder {shared.invite_link}."
    }

    @Test
    fun `a variable that is not set yet explains how to get it`() {
        unresolved("{vars.email_code}") shouldContain "{vars.email_code} is not set. Call get_email_code first."
        unresolved("{vars.phone_code}") shouldContain "{vars.phone_code} is not set. Call get_phone_code first."
        unresolved("{vars.other}") shouldContain "{vars.other} is not set."
        unresolved("{shared.company_code}") shouldContain "{shared.company_code} is not known yet."
    }

    @Test
    fun `every problem of a text is reported once`() {
        val message = unresolved("{a.b} {a.b} {vars.x}")
        message.split("Unknown placeholder {a.b}.").size shouldBe 2
        message shouldContain "{vars.x} is not set."
    }

    @Test
    fun `an admin without department has no department placeholder`() {
        val admin = AgentTestData.runtime(FakeBrowserSession(), AgentTestData.admin)
        resolver.resolve("{self.department}", admin).shouldBeInstanceOf<Resolution.Unresolved>().message shouldContain
            "{self.department} has no value for you."
        resolver.available(admin) shouldContainExactly
            listOf("{self.email}", "{self.password}", "{self.name}", "{self.phone}", "{self.agent_id}", "{self.role}")
    }

    @Test
    fun `available placeholders follow what is set right now`() {
        resolver.available(runtime) shouldContainExactly
            listOf("{self.email}", "{self.password}", "{self.name}", "{self.phone}", "{self.agent_id}", "{self.role}", "{self.department}")
        runtime.variables[AgentVariableKeys.EMAIL_CODE] = "1"
        runtime.shared.put(SharedRunState.COMPANY_CODE, "PTK")
        resolver.available(runtime).takeLast(2) shouldContainExactly listOf("{vars.email_code}", "{shared.company_code}")
    }

    @Test
    fun `a resolved text never prints the secret`() {
        val resolution = resolver.resolve("{self.password}", runtime)
        resolution.toString() shouldNotContain identity.password.reveal()
    }
}
