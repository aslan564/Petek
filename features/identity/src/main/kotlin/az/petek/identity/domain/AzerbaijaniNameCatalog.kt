package az.petek.identity.domain

/**
 * Built-in Azerbaijani names, used after the campaign's own names run out. Surnames are listed in their masculine
 * form; [surnameFor] adds the feminine ending for female first names (Məmmədov → Məmmədova), so generated people
 * read naturally on screen and in the report. Surnames ending in `-zadə` or `-li` have no gender form.
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

    private val femaleKeys: Set<String> = femaleFirstNames.mapTo(HashSet()) { it.lowercase() }

    override fun surnameFor(
        firstName: String,
        surname: String,
    ): String {
        val inflects = surname.endsWith("ov") || surname.endsWith("ev")
        return if (inflects && firstName.lowercase() in femaleKeys) surname + "a" else surname
    }
}
