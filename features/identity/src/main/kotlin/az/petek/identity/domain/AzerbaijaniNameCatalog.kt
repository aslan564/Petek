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

/**
 * Built-in Azerbaijani names, used after the campaign's own names run out. Surnames are listed in their masculine
 * form; [surnameFor] adds the feminine ending for female first names (Məmmədov → Məmmədova), so generated people
 * read naturally on screen and in the report. Surnames ending in `-zadə` or `-li` have no gender form.
 * Large registries add the Azerbaijani patronymic (`Əli Vüqar oğlu Məmmədov`, `Günel Vüqar qızı Məmmədova`).
 */
object AzerbaijaniNameCatalog : NameCatalog {
    internal val maleFirstNames: List<String> =
        listOf(
            "Əli",
            "Vüqar",
            "Rəşad",
            "Elvin",
            "Tural",
            "Orxan",
            "Kamran",
            "Fərid",
            "Nicat",
            "Ramil",
            "Elnur",
            "Şəhriyar",
            "Cavid",
            "Anar",
            "Murad",
            "Rauf",
            "Emin",
            "Səməd",
            "Ülvi",
            "Həsən",
            "Hüseyn",
            "Nurlan",
            "Zaur",
            "Toğrul",
            "Vüsal",
            "İlkin",
            "Kənan",
            "Ceyhun",
            "Elçin",
            "Rüstəm",
            "Mübariz",
            "Əkbər",
            "Pərviz",
            "Taleh",
        )

    internal val femaleFirstNames: List<String> =
        listOf(
            "Günel",
            "Ləman",
            "Aysel",
            "Aynur",
            "Nigar",
            "Səbinə",
            "Könül",
            "Leyla",
            "Nərmin",
            "Türkan",
            "Gülnar",
            "Şəbnəm",
            "Aytən",
            "Xədicə",
            "Zəhra",
            "Fidan",
            "Nərgiz",
            "Sevinc",
            "Aygün",
            "Ülviyyə",
            "Lalə",
            "Gülər",
            "Əsmər",
            "Rəna",
            "Nuranə",
            "Vüsalə",
            "Sona",
            "İlahə",
            "Ayşən",
            "Mələk",
            "Kəmalə",
            "Səidə",
            "Jalə",
            "Çiçək",
        )

    override val firstNames: List<String> = maleFirstNames + femaleFirstNames

    override val surnames: List<String> =
        listOf(
            "Məmmədov",
            "Əliyev",
            "Hüseynov",
            "Həsənov",
            "Quliyev",
            "İsmayılov",
            "Kərimov",
            "Abbasov",
            "Rzayev",
            "Cəfərov",
            "Babayev",
            "Əhmədov",
            "Nəsirov",
            "Qasımov",
            "Hacıyev",
            "Musayev",
            "Orucov",
            "Sadıqov",
            "Süleymanov",
            "Vəliyev",
            "Zeynalov",
            "İbrahimov",
            "Mustafayev",
            "Rəhimov",
            "Şükürov",
            "Bağırov",
            "Novruzov",
            "Salmanov",
            "Məlikov",
            "Əsgərov",
            "Paşayev",
            "Yusifov",
            "Qənbərov",
            "Tağıyev",
            "Nağıyev",
            "Heydərov",
            "Rüstəmov",
            "Əfəndiyev",
            "Mirzəyev",
            "Səfərov",
            "Xəlilov",
            "Əlizadə",
            "Quluzadə",
            "Məmmədli",
        )

    /** Compared like every other name ([NameAllocator.key]), so `İLAHƏ` or `ILAHƏ` given by the user is still female. */
    private val femaleKeys: Set<String> = femaleFirstNames.mapTo(HashSet(), NameAllocator::key)

    /** Fathers are named from the male first names: `Vüqar oğlu`, never `Günel oğlu`. */
    override val fatherNames: List<String> get() = maleFirstNames

    override fun surnameFor(
        firstName: String,
        surname: String,
    ): String {
        val inflects = surname.endsWith("ov") || surname.endsWith("ev")
        return if (inflects && isFemale(firstName)) surname + "a" else surname
    }

    /** `Vüqar oğlu` (son of Vüqar) or, for female first names, `Vüqar qızı` (daughter of Vüqar). */
    override fun patronymic(
        firstName: String,
        fatherName: String,
    ): String = if (isFemale(firstName)) "$fatherName qızı" else "$fatherName oğlu"

    private fun isFemale(firstName: String): Boolean = NameAllocator.key(firstName) in femaleKeys
}
