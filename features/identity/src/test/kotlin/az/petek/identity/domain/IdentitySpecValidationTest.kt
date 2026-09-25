package az.petek.identity.domain

import az.petek.identity.IdentityTestData.RUN_TAG
import az.petek.identity.IdentityTestData.SmallCatalog
import az.petek.identity.IdentityTestData.generator
import az.petek.identity.IdentityTestData.spec
import io.kotest.assertions.throwables.shouldThrow
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
    fun `negative counts are rejected`() {
        conflict(spec(managers = -1, employees = 30)) shouldContain "managers must not be negative, was -1"
        conflict(spec(inviteCount = -1, companyCodeCount = 30)) shouldContain "invite count must not be negative, was -1"
    }

    @Test
    fun `tester counts outside a01 to a999 are rejected`() {
        conflict(spec(testers = 0, names = emptyList(), managers = 0, employees = -1)) shouldContain
            "testers must be between 1 and 999"
        conflict(spec(testers = 1000, managers = 5)) shouldContain "testers must be between 1 and 999"
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
    fun `a catalog too small for the testers is rejected`() {
        val small = SmallCatalog(firstNames = listOf("Anar", "Emin"), surnames = listOf("Kərimov", "Əliyev"))
        conflict(spec(testers = 10, names = emptyList()), small) shouldContain
            "the name catalog offers only 4 unique names but 10 more are needed"
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
