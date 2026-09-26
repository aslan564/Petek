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

package az.petek.identity.domain

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch
import org.junit.jupiter.api.Test

class AsciiSlugTest {
    @Test
    fun `every Azerbaijani letter is transliterated in both cases`() {
        val table =
            mapOf(
                "ə" to "e",
                "Ə" to "e",
                "ı" to "i",
                "I" to "i",
                "İ" to "i",
                "ö" to "o",
                "Ö" to "o",
                "ü" to "u",
                "Ü" to "u",
                "ş" to "s",
                "Ş" to "s",
                "ç" to "c",
                "Ç" to "c",
                "ğ" to "g",
                "Ğ" to "g",
            )
        table.forEach { (letter, ascii) -> AsciiSlug.of("a${letter}b") shouldBe "a${ascii}b" }
    }

    @Test
    fun `Azerbaijani names become readable ASCII`() {
        val names =
            mapOf(
                "Əli" to "eli",
                "Şəhriyar" to "sehriyar",
                "Günel" to "gunel",
                "Çingiz" to "cingiz",
                "Ağa" to "aga",
                "İlkin" to "ilkin",
                "Işıq" to "isiq",
                "Ömər" to "omer",
                "Ülviyyə" to "ulviyye",
                "XƏDİCƏ" to "xedice",
            )
        names.forEach { (name, slug) -> AsciiSlug.of(name) shouldBe slug }
    }

    @Test
    fun `accents of other Latin letters are dropped`() {
        AsciiSlug.of("José") shouldBe "jose"
        AsciiSlug.of("Zoë") shouldBe "zoe"
    }

    @Test
    fun `runs of other characters collapse into one hyphen and are trimmed at the ends`() {
        AsciiSlug.of("Ay  Nur") shouldBe "ay-nur"
        AsciiSlug.of("O'Brien") shouldBe "o-brien"
        AsciiSlug.of("--Anar__") shouldBe "anar"
        AsciiSlug.of("Anar2") shouldBe "anar2"
    }

    @Test
    fun `a name without any ASCII letters falls back to a fixed word`() {
        AsciiSlug.of("Анар") shouldBe AsciiSlug.FALLBACK
        AsciiSlug.of("") shouldBe AsciiSlug.FALLBACK
    }

    @Test
    fun `long names are cut without leaving a trailing hyphen`() {
        val slug = AsciiSlug.of("a".repeat(31) + " bcdef")
        slug shouldBe "a".repeat(31)
        AsciiSlug.of("Ə".repeat(100)).length shouldBe 32
    }

    @Test
    fun `the result only ever contains lower-case letters, digits and inner hyphens`() {
        listOf("Əli Kərimov", "ŞƏHRİYAR!", "a.b@c", "  Günel  ", "İ̇", "123").forEach {
            AsciiSlug.of(it) shouldMatch Regex("[a-z0-9]+(-[a-z0-9]+)*")
        }
    }
}
