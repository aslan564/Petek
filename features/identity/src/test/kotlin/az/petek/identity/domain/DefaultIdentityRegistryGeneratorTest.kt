package az.petek.identity.domain

import az.petek.core.ids.AgentId
import az.petek.core.ids.RunTag
import az.petek.core.model.RegistrationMode
import az.petek.core.model.Role
import az.petek.core.security.Secret
import az.petek.identity.IdentityTestData
import az.petek.identity.IdentityTestData.DEPARTMENTS
import az.petek.identity.IdentityTestData.OTHER_RUN_TAG
import az.petek.identity.IdentityTestData.RUN_TAG
import az.petek.identity.IdentityTestData.SmallCatalog
import az.petek.identity.IdentityTestData.generator
import az.petek.identity.IdentityTestData.spec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeIn
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotContainDuplicates
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldMatch
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test

class DefaultIdentityRegistryGeneratorTest {
    private val catalog = AzerbaijaniNameCatalog
    private val plan = generator().generate(spec(), RUN_TAG)
    private val identities = plan.identities

    private fun spread(counts: Collection<Int>) = counts.max() - counts.min()

    @Test
    fun `the same spec, run tag and secret always produce the same plan`() {
        val again = generator().generate(spec(), RUN_TAG)
        again shouldBe plan
        again.runTag shouldBe RUN_TAG
    }

    @Test
    fun `another run tag keeps the people but gives them new e-mails, passwords and phones`() {
        val other = generator().generate(spec(), OTHER_RUN_TAG).identities
        other.map { it.displayName } shouldBe identities.map { it.displayName }
        other.map { it.role } shouldBe identities.map { it.role }
        other.map { it.department } shouldBe identities.map { it.department }
        other.map { it.registration } shouldBe identities.map { it.registration }
        other.zip(identities).forEach { (a, b) ->
            a.email shouldNotBe b.email
            a.email shouldBe b.email.replace(".${RUN_TAG.value}.", ".${OTHER_RUN_TAG.value}.")
            a.password shouldNotBe b.password
        }
        other.map { it.phone } shouldNotBe identities.map { it.phone }
    }

    @Test
    fun `another password secret only changes the passwords`() {
        val other = generator(secret = "another-secret").generate(spec(), RUN_TAG).identities
        other.map { it.copy(password = Secret("x")) } shouldBe identities.map { it.copy(password = Secret("x")) }
        other.zip(identities).forEach { (a, b) -> a.password shouldNotBe b.password }
    }

    @Test
    fun `another seed chooses other catalog names but keeps the given ones`() {
        val other = generator().generate(spec(seed = 7), RUN_TAG).identities
        other.take(5).map { it.displayName.substringBefore(' ') } shouldBe IdentityTestData.GIVEN_NAMES
        other.drop(5).map { it.displayName } shouldNotBe identities.drop(5).map { it.displayName }
    }

    @Test
    fun `agent ids run from a01 with the admin first, then managers, then employees`() {
        identities.map { it.agentId } shouldBe (1..30).map { AgentId.of(it) }
        identities.map { it.role } shouldBe listOf(Role.ADMIN) + List(5) { Role.MANAGER } + List(24) { Role.EMPLOYEE }
    }

    @Test
    fun `the admin owns the company and belongs to no department`() {
        val admin = identities.first()
        admin.agentId shouldBe AgentId("a01")
        admin.registration shouldBe RegistrationMode.OWNER
        admin.department shouldBe null
        identities.drop(1).forEach {
            it.registration shouldNotBe RegistrationMode.OWNER
            it.department shouldBeIn DEPARTMENTS
        }
    }

    @Test
    fun `five managers lead five different departments in order`() {
        identities.filter { it.role == Role.MANAGER }.map { it.department } shouldBe DEPARTMENTS
    }

    @Test
    fun `employees are spread over the departments within one of each other`() {
        val perDepartment = identities.filter { it.role == Role.EMPLOYEE }.groupingBy { it.department }.eachCount()
        perDepartment.keys shouldContainExactlyInAnyOrder DEPARTMENTS
        spread(perDepartment.values) shouldBeLessThanOrEqual 1
    }

    @Test
    fun `head counts stay balanced when managers and departments differ`() {
        listOf(Triple(3, 17, 4), Triple(7, 12, 3), Triple(0, 9, 4), Triple(2, 0, 5)).forEach { (managers, employees, d) ->
            val departments = DEPARTMENTS.take(d)
            val result =
                generator()
                    .generate(
                        spec(
                            testers = 1 + managers + employees,
                            names = emptyList(),
                            managers = managers,
                            employees = employees,
                            departments = departments,
                        ),
                        RUN_TAG,
                    ).identities
            val employeesPerDepartment =
                departments.map { dept -> result.count { it.role == Role.EMPLOYEE && it.department == dept } }
            val headCount = departments.map { dept -> result.count { it.department == dept } }
            spread(employeesPerDepartment) shouldBeLessThanOrEqual 1
            spread(headCount) shouldBeLessThanOrEqual 1
        }
    }

    @Test
    fun `more managers than departments wrap around the departments`() {
        val result =
            generator().generate(spec(testers = 10, managers = 7, departments = listOf("IT", "HR", "Satış")), RUN_TAG)
        result.identities.filter { it.role == Role.MANAGER }.map { it.department } shouldBe
            listOf("IT", "HR", "Satış", "IT", "HR", "Satış", "IT")
    }

    @Test
    fun `registration quotas are met exactly`() {
        val modes = identities.drop(1).groupingBy { it.registration }.eachCount()
        modes shouldBe mapOf(RegistrationMode.INVITE to 15, RegistrationMode.COMPANY_CODE to 14)
    }

    @Test
    fun `every department gets both invitations and company codes`() {
        (1L..20L).forEach { seed ->
            val result = generator().generate(spec(seed = seed), RUN_TAG).identities.drop(1)
            result.groupBy { it.department }.forEach { (department, members) ->
                val modes = members.groupingBy { it.registration }.eachCount()
                modes.keys shouldBe setOf(RegistrationMode.INVITE, RegistrationMode.COMPANY_CODE)
                withClue("department $department, seed $seed") { spread(modes.values) shouldBeLessThanOrEqual 1 }
            }
        }
    }

    @Test
    fun `registration modes are shuffled rather than handed out by agent id`() {
        val modes = identities.drop(1).map { it.registration }
        modes shouldNotBe List(15) { RegistrationMode.INVITE } + List(14) { RegistrationMode.COMPANY_CODE }
        val managerModes = (1L..20L).map { seed -> generator().generate(spec(seed = seed), RUN_TAG).identities[1].registration }
        managerModes.toSet() shouldHaveSize 2
    }

    @Test
    fun `quotas of zero give everyone the other registration mode`() {
        val allInvite = generator().generate(spec(inviteCount = 29, companyCodeCount = 0), RUN_TAG).identities.drop(1)
        allInvite.map { it.registration }.toSet() shouldBe setOf(RegistrationMode.INVITE)
        val allCode = generator().generate(spec(inviteCount = 0, companyCodeCount = 29), RUN_TAG).identities.drop(1)
        allCode.map { it.registration }.toSet() shouldBe setOf(RegistrationMode.COMPANY_CODE)
    }

    @Test
    fun `given names come first in order and single words get a catalog surname`() {
        identities.take(5).forEachIndexed { i, identity ->
            val (first, surname) = identity.displayName.split(' ')
            first shouldBe IdentityTestData.GIVEN_NAMES[i]
            surname shouldBeIn catalog.surnames.map { catalog.surnameFor(first, it) }
        }
    }

    @Test
    fun `catalog names fill the remaining testers`() {
        identities.drop(5).forEach { identity ->
            val (first, surname) = identity.displayName.split(' ')
            first shouldBeIn catalog.firstNames
            surname shouldBeIn catalog.surnames.map { catalog.surnameFor(first, it) }
        }
    }

    @Test
    fun `a given full name is used exactly as written`() {
        val result = generator().generate(spec(names = listOf("Əli Kərimov", "Günel", "Nigar Səfərova")), RUN_TAG)
        result.identities[0].displayName shouldBe "Əli Kərimov"
        result.identities[0].email shouldBe "eli.k7x2.a01@test.kadrohr.com"
        result.identities[1].displayName shouldStartWith "Günel "
        result.identities[2].displayName shouldBe "Nigar Səfərova"
    }

    @Test
    fun `given names are normalized before use`() {
        val result = generator().generate(spec(names = listOf("  Əli   Kərimov ", " Günel")), RUN_TAG)
        result.identities[0].displayName shouldBe "Əli Kərimov"
        result.identities[1].displayName shouldStartWith "Günel "
    }

    @Test
    fun `a given first name never takes the display name of a given full name`() {
        val small = SmallCatalog(firstNames = listOf("Anar", "Emin"), surnames = listOf("Kərimov", "Əliyev"))
        val result =
            generator(small).generate(spec(testers = 4, managers = 1, names = listOf("Əli", "Əli Kərimov")), RUN_TAG)
        result.identities.map { it.displayName }.take(2) shouldBe listOf("Əli Əliyev", "Əli Kərimov")
        result.identities.map { it.displayName.lowercase() }.shouldNotContainDuplicates()
    }

    @Test
    fun `catalog first names are not repeated while unused ones are left`() {
        identities.map { it.displayName.substringBefore(' ') }.shouldNotContainDuplicates()
    }

    @Test
    fun `surnames are not repeated while the catalog has unused ones`() {
        val surnameBases = identities.map { it.displayName.substringAfter(' ').removeSuffix("a") }
        surnameBases.shouldNotContainDuplicates()
    }

    @Test
    fun `female testers carry the feminine surname form`() {
        val many = generator().generate(spec(testers = 200, names = emptyList()), RUN_TAG).identities
        many.forEach { identity ->
            val (first, surname) = identity.displayName.split(' ')
            if (first in catalog.femaleFirstNames) {
                (surname.endsWith("ov") || surname.endsWith("ev")) shouldBe false
            } else {
                (surname.endsWith("ova") || surname.endsWith("eva")) shouldBe false
            }
        }
    }

    @Test
    fun `display names, e-mails and phones are unique for 100 testers`() {
        val many = generator().generate(spec(testers = 100), RUN_TAG).identities
        many shouldHaveSize 100
        many.map { it.displayName.lowercase() }.shouldNotContainDuplicates()
        many.map { it.email }.shouldNotContainDuplicates()
        many.map { it.phone }.shouldNotContainDuplicates()
        many.map { it.password }.shouldNotContainDuplicates()
    }

    @Test
    fun `the largest registry keeps every value unique`() {
        val many = generator().generate(spec(testers = 999, managers = 20), RUN_TAG).identities
        many.last().agentId shouldBe AgentId("a999")
        many.map { it.displayName.lowercase() }.shouldNotContainDuplicates()
        many.map { it.email }.shouldNotContainDuplicates()
        many.map { it.phone }.shouldNotContainDuplicates()
    }

    @Test
    fun `e-mails combine the ASCII first name, run tag and agent id`() {
        identities[0].email shouldBe "eli.k7x2.a01@test.kadrohr.com"
        identities[1].email shouldBe "veli.k7x2.a02@test.kadrohr.com"
        identities[3].email shouldBe "cemil.k7x2.a04@test.kadrohr.com"
        identities.forEach { identity ->
            identity.email shouldMatch Regex("[a-z0-9-]+\\.k7x2\\.${identity.agentId.value}@test\\.kadrohr\\.com")
            identity.email shouldStartWith AsciiSlug.of(identity.displayName.substringBefore(' ')) + "."
        }
    }

    @Test
    fun `the mail domain is trimmed and lower-cased`() {
        val result = generator().generate(spec(mailDomain = " Test.KadroHR.com "), RUN_TAG)
        result.identities.forEach { it.email.substringAfter('@') shouldBe "test.kadrohr.com" }
    }

    @Test
    fun `phones are fake Azerbaijani numbers outside the allocated subscriber ranges`() {
        identities.forEach { it.phone shouldMatch Regex("\\+99450[01]\\d{6}") }
        generator().generate(spec(), RUN_TAG).identities.map { it.phone } shouldBe identities.map { it.phone }
    }

    @Test
    fun `passwords come from the deriver for the run tag and agent id`() {
        val deriver = PasswordDeriver { tag, agentId -> Secret("$tag/$agentId") }
        val result = DefaultIdentityRegistryGenerator(catalog, deriver).generate(spec(), RUN_TAG)
        result.identities.forEach { it.password shouldBe Secret("k7x2/${it.agentId}") }
    }

    @Test
    fun `new identities start as planned without a storage state`() {
        identities.forEach {
            it.status shouldBe IdentityStatus.PLANNED
            it.storageStatePath shouldBe null
        }
    }

    @Test
    fun `a single tester is just the admin`() {
        val result =
            generator().generate(
                spec(testers = 1, managers = 0, employees = 0, inviteCount = 0, companyCodeCount = 0, names = listOf("Əli")),
                RUN_TAG,
            )
        result.identities shouldHaveSize 1
        result.identities.single().role shouldBe Role.ADMIN
    }

    @Test
    fun `department names are normalized`() {
        val result = generator().generate(spec(departments = listOf(" IT ", "Satış  Şöbəsi")), RUN_TAG)
        result.identities.mapNotNull { it.department }.toSet() shouldBe setOf("IT", "Satış Şöbəsi")
    }

    @Test
    fun `a catalog without an unused surname for a given name is a conflict`() {
        val small = SmallCatalog(firstNames = listOf("Anar"), surnames = listOf("Kərimov"))
        val error =
            shouldThrow<IdentityConflictException> {
                generator(small).generate(
                    spec(testers = 2, managers = 1, employees = 0, names = listOf("Əli", "Əli Kərimov")),
                    RunTag("aaaa"),
                )
            }
        error.message shouldContain "no unused surname left for the given name 'Əli'"
    }

    @Test
    fun `a catalog exhausted by given full names is a conflict`() {
        val small = SmallCatalog(firstNames = listOf("Anar"), surnames = listOf("Kərimov", "Əliyev"))
        val error =
            shouldThrow<IdentityConflictException> {
                generator(small).generate(
                    spec(testers = 3, managers = 1, employees = 1, names = listOf("Anar Kərimov")),
                    RUN_TAG,
                )
            }
        error.message shouldContain "the name catalog is too small"
    }
}
