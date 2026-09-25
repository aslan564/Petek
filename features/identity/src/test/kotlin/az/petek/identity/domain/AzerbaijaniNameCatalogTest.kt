package az.petek.identity.domain

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldNotContainDuplicates
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class AzerbaijaniNameCatalogTest {
    private val catalog = AzerbaijaniNameCatalog

    @Test
    fun `the catalog has at least 60 first names and 40 surnames`() {
        catalog.firstNames.size shouldBeGreaterThanOrEqual 60
        catalog.surnames.size shouldBeGreaterThanOrEqual 40
    }

    @Test
    fun `first names and surnames have no duplicates, ignoring case`() {
        catalog.firstNames.map(NameAllocator::key).shouldNotContainDuplicates()
        catalog.surnames.map(NameAllocator::key).shouldNotContainDuplicates()
    }

    @Test
    fun `first names mix both genders`() {
        catalog.maleFirstNames.size shouldBeGreaterThanOrEqual 25
        catalog.femaleFirstNames.size shouldBeGreaterThanOrEqual 25
        catalog.maleFirstNames.intersect(catalog.femaleFirstNames.toSet()).shouldBeEmpty()
        catalog.firstNames shouldBe catalog.maleFirstNames + catalog.femaleFirstNames
    }

    @Test
    fun `names are spelled with proper Azerbaijani letters`() {
        catalog.firstNames shouldContainAll listOf("Əli", "Günel", "Ləman", "Şəhriyar", "İlkin", "Toğrul", "Çiçək")
        catalog.surnames shouldContainAll listOf("Məmmədov", "Hüseynov", "Əliyev", "Qasımov", "Şükürov")
        val azerbaijaniLetters = "əƏıİöÖüÜşŞçÇğĞ".toSet()
        catalog.firstNames.count { name -> name.any { it in azerbaijaniLetters } } shouldBeGreaterThanOrEqual 30
    }

    @Test
    fun `names are single capitalized words without surrounding spaces`() {
        (catalog.firstNames + catalog.surnames).forEach { name ->
            name.trim() shouldBe name
            name.contains(' ') shouldBe false
            name.first().isUpperCase() shouldBe true
        }
    }

    @Test
    fun `female first names take the feminine surname form`() {
        catalog.surnameFor("Günel", "Məmmədov") shouldBe "Məmmədova"
        catalog.surnameFor("Ləman", "Hüseynov") shouldBe "Hüseynova"
        catalog.surnameFor("Aysel", "Əliyev") shouldBe "Əliyeva"
        catalog.surnameFor("günel", "Mustafayev") shouldBe "Mustafayeva"
    }

    @Test
    fun `female first names are recognized whatever their letter case`() {
        catalog.surnameFor("İLAHƏ", "Məmmədov") shouldBe "Məmmədova"
        catalog.surnameFor("ILAHƏ", "Məmmədov") shouldBe "Məmmədova"
        catalog.surnameFor("TÜRKAN", "Quliyev") shouldBe "Quliyeva"
        catalog.surnameFor("SƏBİNƏ", "Kərimov") shouldBe "Kərimova"
    }

    @Test
    fun `male and unknown first names keep the masculine form`() {
        catalog.surnameFor("Əli", "Məmmədov") shouldBe "Məmmədov"
        catalog.surnameFor("Sahil", "Hüseynov") shouldBe "Hüseynov"
    }

    @Test
    fun `surnames without a gender form never change`() {
        listOf("Əlizadə", "Quluzadə", "Məmmədli").forEach { surname ->
            catalog.surnameFor("Günel", surname) shouldBe surname
            catalog.surnameFor("Əli", surname) shouldBe surname
        }
    }

    @Test
    fun `every surname has its gender form distinct from the other surnames`() {
        catalog.femaleFirstNames.forEach { first ->
            catalog.surnames.map { catalog.surnameFor(first, it) }.shouldNotContainDuplicates()
        }
    }

    @Test
    fun `fathers are named from the male first names only`() {
        catalog.fatherNames shouldBe catalog.maleFirstNames
    }

    @Test
    fun `the patronymic says son of or daughter of the father`() {
        catalog.patronymic("Əli", "Vüqar") shouldBe "Vüqar oğlu"
        catalog.patronymic("Günel", "Vüqar") shouldBe "Vüqar qızı"
        catalog.patronymic("İLAHƏ", "Rəşad") shouldBe "Rəşad qızı"
        catalog.patronymic("Sahil", "Rəşad") shouldBe "Rəşad oğlu"
    }

    @Test
    fun `distinct fathers give distinct patronymics for every first name`() {
        catalog.firstNames.forEach { first ->
            catalog.fatherNames.map { catalog.patronymic(first, it) }.shouldNotContainDuplicates()
        }
    }
}
