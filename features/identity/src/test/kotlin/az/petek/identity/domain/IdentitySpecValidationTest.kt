package az.petek.identity.domain

import az.petek.identity.IdentityTestData.RUN_TAG
import az.petek.identity.IdentityTestData.SmallCatalog
import az.petek.identity.IdentityTestData.generator
import az.petek.identity.IdentityTestData.spec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test

/** Every rule that must stop a run before it starts (docs/PLAN.md: "eyni adı iki dəfə verəndə run başlamır"). */
class IdentitySpecValidationTest {
    private fun conflict(
        spec: IdentitySpec,
        catalog: NameCatalog = AzerbaijaniNameCatalog,
    ): String = shouldThrow<IdentityConflictException> { generator(catalog).generate(spec, RUN_TAG) }.message.orEmpty()

    @Test
    fun `a name given twice stops the run and the message names it`() {
        val message = conflict(spec(names = listOf("Əli", "Vəli", "Əli")))
        message shouldStartWith "Identity registry cannot be built:"
        message shouldContain "name 'Əli' is given more than once"
        message shouldNotContain "Vəli"
    }

    @Test
    fun `names differing only in case or spacing are duplicates`() {
        conflict(spec(names = listOf("Əli Kərimov", "əli  kərimov"))) shouldContain "name 'Əli Kərimov' is given more than once"
        conflict(spec(names = listOf("Günel", " GÜNEL "))) shouldContain "name 'Günel' is given more than once"
    }

    @Test
    fun `blank names are rejected`() {
        conflict(spec(names = listOf("Əli", "   "))) shouldContain "tester names must not be blank"
    }

    @Test
    fun `more names than testers are rejected`() {
        conflict(spec(testers = 3, managers = 1, names = listOf("A", "B", "C", "D"))) shouldContain
            "4 names are given but there are only 3 testers"
    }

    @Test
    fun `overlong names and departments are rejected`() {
        val long = "Ə".repeat(101)
        conflict(spec(names = listOf(long))) shouldContain "is longer than 100 characters"
        conflict(spec(departments = listOf("IT", long))) shouldContain "is longer than 100 characters"
    }

    @Test
    fun `testers must equal the sum of the roles`() {
        conflict(spec(testers = 30, managers = 5, employees = 20)) shouldContain
            "roles add up to 26 (1 admin + 5 managers + 20 employees) but testers is 30"
    }

    @Test
    fun `exactly one admin is required`() {
        conflict(spec(admins = 0, employees = 25)) shouldContain "exactly 1 admin is supported, was 0"
        conflict(spec(admins = 2, employees = 23)) shouldContain "exactly 1 admin is supported, was 2"
    }

    @Test
    fun `the registration quota must cover every manager and employee`() {
        conflict(spec(inviteCount = 15, companyCodeCount = 13)) shouldContain
            "registration quota adds up to 28 (15 invite + 13 company code) but there are 29 managers and employees"
    }

    @Test
    fun `the invite count must cover every manager, because managers always join by invitation`() {
        val message = conflict(spec(inviteCount = 4, companyCodeCount = 25))
        message shouldContain "invite count 4 is less than the 5 managers; managers always join by invitation"
        message shouldNotContain "registration quota adds up"
        conflict(spec(managers = 3, inviteCount = 0, companyCodeCount = 29)) shouldContain "invite count 0 is less than the 3 managers"
    }

    @Test
    fun `an invite count equal to the managers is enough`() {
        generator().generate(spec(inviteCount = 5, companyCodeCount = 24), RUN_TAG).identities shouldHaveSize 30
        generator().generate(spec(managers = 0, inviteCount = 0, companyCodeCount = 29), RUN_TAG).identities shouldHaveSize 30
    }

    @Test
    fun `negative counts are rejected`() {
        conflict(spec(managers = -1, employees = 30)) shouldContain "managers must not be negative, was -1"
        conflict(spec(inviteCount = -1, companyCodeCount = 30)) shouldContain "invite count must not be negative, was -1"
    }

    @Test
    fun `a tester count below one is rejected`() {
        conflict(spec(testers = 0, names = emptyList(), managers = 0, employees = -1)) shouldContain
            "testers must be at least 1, was 0"
    }

    @Test
    fun `there is no fixed maximum of testers below the fake phone range`() {
        val plan = generator().generate(spec(testers = 1_000, managers = 20), RUN_TAG)

        plan.identities shouldHaveSize 1_000
        plan.identities
            .last()
            .agentId.value shouldBe "a1000"
    }

    @Test
    fun `more testers than distinct fake phone numbers are rejected`() {
        conflict(spec(testers = 2_000_001, managers = 5, names = emptyList())) shouldContain
            "testers must not exceed 2000000, the number of distinct fake phone numbers (one per tester), was 2000001"
    }

    @Test
    fun `at least one department is required`() {
        conflict(spec(departments = emptyList())) shouldContain "at least one department is required"
    }

    @Test
    fun `departments must be unique ignoring case`() {
        conflict(spec(departments = listOf("IT", "HR", "it"))) shouldContain "department 'IT' is listed more than once"
    }

    @Test
    fun `Azerbaijani capitals count as the same letters when comparing departments`() {
        conflict(spec(departments = listOf("IT", "Satış", "SATIŞ"))) shouldContain "department 'Satış' is listed more than once"
        conflict(spec(departments = listOf("Əməliyyat", "ƏMƏLİYYAT"))) shouldContain
            "department 'Əməliyyat' is listed more than once"
    }

    @Test
    fun `Azerbaijani capitals count as the same letters when comparing names`() {
        conflict(spec(names = listOf("Əli", "ƏLİ"))) shouldContain "name 'Əli' is given more than once"
        conflict(spec(names = listOf("İlkin Qasımov", "ilkin QASIMOV"))) shouldContain
            "name 'İlkin Qasımov' is given more than once"
    }

    @Test
    fun `composed and decomposed spellings of a letter are the same name`() {
        conflict(spec(names = listOf("Günel", "Günel"))) shouldContain "is given more than once"
    }

    @Test
    fun `blank departments are rejected`() {
        conflict(spec(departments = listOf("IT", " "))) shouldContain "department names must not be blank"
    }

    @Test
    fun `an invalid mail domain is rejected`() {
        listOf("", "test@kadrohr.com", "-test.com", "test..com", "test_kadrohr.com", "a".repeat(201)).forEach {
            conflict(spec(mailDomain = it)) shouldContain "is not a valid domain name"
        }
    }

    @Test
    fun `a catalog without first names or surnames cannot name the testers without a given name`() {
        val noFirstNames = SmallCatalog(firstNames = emptyList(), surnames = listOf("Kərimov"))
        conflict(spec(testers = 10, names = listOf("Əli")), noFirstNames) shouldContain
            "the name catalog has no first names or no surnames, but 9 more names are needed"
        val noSurnames = SmallCatalog(firstNames = listOf("Anar"), surnames = emptyList())
        conflict(spec(testers = 10, names = listOf("Əli Kərimov")), noSurnames) shouldContain
            "the name catalog has no first names or no surnames, but 9 more names are needed"
    }

    @Test
    fun `an empty catalog is fine when the campaign names every tester in full`() {
        val empty = SmallCatalog(firstNames = emptyList(), surnames = emptyList())
        val names = listOf("Əli Kərimov", "Vəli Həsənov", "Günel Quliyeva")

        val plan = generator(empty).generate(spec(testers = 3, managers = 1, names = names), RUN_TAG)

        plan.identities.map { it.displayName } shouldBe names
    }

    @Test
    fun `a catalog without surnames cannot complete a given first name`() {
        val noSurnames = SmallCatalog(firstNames = listOf("Anar"), surnames = emptyList())
        conflict(spec(testers = 2, managers = 1, names = listOf("Əli", "Vəli Kərimov")), noSurnames) shouldContain
            "the name catalog has no surnames for the given first names"
    }

    @Test
    fun `every problem is reported at once`() {
        val message = conflict(spec(admins = 2, employees = 23, names = listOf("Əli", "Əli"), departments = emptyList()))
        message shouldContain "exactly 1 admin is supported"
        message shouldContain "name 'Əli' is given more than once"
        message shouldContain "at least one department is required"
    }
}
