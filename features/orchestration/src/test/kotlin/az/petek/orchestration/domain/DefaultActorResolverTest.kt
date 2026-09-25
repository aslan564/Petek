package az.petek.orchestration.domain

import az.petek.core.ids.AgentId
import az.petek.core.ids.RunTag
import az.petek.core.model.RegistrationMode
import az.petek.core.model.Role
import az.petek.core.security.Secret
import az.petek.identity.domain.Identity
import az.petek.identity.domain.IdentitySpec
import az.petek.orchestration.testing.SimpleIdentityGenerator
import az.petek.orchestration.testing.actors
import az.petek.orchestration.testing.admin
import az.petek.orchestration.testing.employees
import az.petek.orchestration.testing.managers
import az.petek.orchestration.testing.selector
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class DefaultActorResolverTest {
    private val resolver = DefaultActorResolver()

    // a01 admin; a02 manager IT, a03 manager HR, a04 manager Satış; a05.. employees round-robin IT, HR, Satış.
    private val registry: List<Identity> =
        SimpleIdentityGenerator()
            .generate(
                IdentitySpec(
                    testers = 13,
                    seed = 1,
                    names = emptyList(),
                    admins = 1,
                    managers = 3,
                    employees = 9,
                    departments = listOf("IT", "HR", "Satış"),
                    inviteCount = 6,
                    companyCodeCount = 6,
                    mailDomain = "test.example.test",
                ),
                RunTag("k7x2"),
            ).identities

    private fun ids(result: List<Identity>) = result.map { it.agentId.value }

    @Test
    fun `a bare role selects everyone with that role`() {
        ids(resolver.resolve(admin(), registry)) shouldBe listOf("a01")
        ids(resolver.resolve(managers(), registry)) shouldBe listOf("a02", "a03", "a04")
        ids(resolver.resolve(employees(), registry)) shouldBe (5..13).map { AgentId.of(it).value }
    }

    @Test
    fun `a department filter is case-insensitive`() {
        ids(resolver.resolve(managers("it"), registry)) shouldBe listOf("a02")
        ids(resolver.resolve(employees("SATIŞ"), registry)) shouldBe listOf("a07", "a10", "a13")
        ids(resolver.resolve(employees("  HR "), registry)) shouldBe listOf("a06", "a09", "a12")
    }

    @Test
    fun `the wildcard department selects everyone of the role`() {
        ids(resolver.resolve(employees("*"), registry)) shouldBe ids(resolver.resolve(employees(), registry))
    }

    @Test
    fun `a department nobody works in selects nobody`() {
        resolver.resolve(employees("Maliyyə"), registry).shouldBeEmpty()
    }

    @Test
    fun `the registration filter keeps only that registration mode`() {
        val invited = resolver.resolve(actors(selector(Role.EMPLOYEE, registration = RegistrationMode.INVITE)), registry)
        val byCode = resolver.resolve(actors(selector(Role.EMPLOYEE, registration = RegistrationMode.COMPANY_CODE)), registry)

        invited.all { it.registration == RegistrationMode.INVITE } shouldBe true
        byCode.all { it.registration == RegistrationMode.COMPANY_CODE } shouldBe true
        (ids(invited) + ids(byCode)).sorted() shouldBe ids(resolver.resolve(employees(), registry))
    }

    @Test
    fun `nth is the 1-based position in the filtered list ordered by agent id`() {
        ids(resolver.resolve(employees("IT", nth = 1), registry)) shouldBe listOf("a05")
        ids(resolver.resolve(employees("IT", nth = 2), registry)) shouldBe listOf("a08")
        ids(resolver.resolve(employees(nth = 3), registry)) shouldBe listOf("a07")
    }

    @Test
    fun `nth combines with the registration filter`() {
        val invitedIt = registry.filter { it.role == Role.EMPLOYEE && it.department == "IT" && it.registration == RegistrationMode.INVITE }
        val result = resolver.resolve(actors(selector(Role.EMPLOYEE, "IT", RegistrationMode.INVITE, nth = 1)), registry)

        result shouldBe listOf(invitedIt.minBy { it.agentId.index })
    }

    @Test
    fun `an nth past the end or below one selects nobody for that selector`() {
        resolver.resolve(employees("IT", nth = 4), registry).shouldBeEmpty()
        resolver.resolve(employees("IT", nth = 0), registry).shouldBeEmpty()
        ids(resolver.resolve(actors(selector(Role.EMPLOYEE, "IT", nth = 9), selector(Role.ADMIN)), registry)) shouldBe listOf("a01")
    }

    @Test
    fun `a union is de-duplicated and ordered by agent id`() {
        val expression =
            actors(
                selector(Role.EMPLOYEE, "HR"),
                selector(Role.MANAGER),
                selector(Role.EMPLOYEE, "hr", nth = 1),
                selector(Role.ADMIN),
            )

        ids(resolver.resolve(expression, registry)) shouldBe listOf("a01", "a02", "a03", "a04", "a06", "a09", "a12")
    }

    @Test
    fun `the result does not depend on the order of the input`() {
        val expression = actors(selector(Role.EMPLOYEE, "IT", nth = 2), selector(Role.MANAGER, "HR"))

        resolver.resolve(expression, registry.shuffled(kotlin.random.Random(7))) shouldBe resolver.resolve(expression, registry)
    }

    @Test
    fun `agent ids of any length sort numerically`() {
        fun employee(index: Int) =
            Identity(
                agentId = AgentId.of(index),
                displayName = "E$index",
                email = "e$index@test.example.test",
                password = Secret("x"),
                phone = "+994500000000",
                role = Role.EMPLOYEE,
                department = "IT",
                registration = RegistrationMode.INVITE,
            )
        val many = listOf(employee(1000), employee(100), employee(99), employee(101), employee(999), employee(10))

        ids(resolver.resolve(employees(), many)) shouldBe listOf("a10", "a99", "a100", "a101", "a999", "a1000")
        ids(resolver.resolve(employees(nth = 3), many)) shouldBe listOf("a100")
        ids(resolver.resolve(employees(nth = 6), many)) shouldBe listOf("a1000")
    }

    @Test
    fun `the admin has no department so department filters never select it`() {
        resolver.resolve(actors(selector(Role.ADMIN, "IT")), registry).shouldBeEmpty()
    }
}
