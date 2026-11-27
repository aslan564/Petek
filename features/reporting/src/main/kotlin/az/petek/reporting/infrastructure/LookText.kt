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

package az.petek.reporting.infrastructure

import az.petek.reporting.domain.RunComparison
import az.petek.reporting.domain.visual.BandKind
import az.petek.reporting.domain.visual.IgnoredBy
import az.petek.reporting.domain.visual.LookChange
import az.petek.reporting.domain.visual.LookComparison
import az.petek.reporting.domain.visual.LookFact
import az.petek.reporting.domain.visual.LookFactKind
import az.petek.reporting.domain.visual.LookReason
import az.petek.reporting.domain.visual.LookSide
import az.petek.reporting.domain.visual.VisualGate
import az.petek.reporting.domain.visual.VisualThresholds
import java.util.Locale

/** How a comparison names its page looks to the owner (Azerbaijani, like the report; docs/adr/0014). */
internal object LookText {
    const val TITLE = "Görünüş"
    const val CHANGED_TILE = "Görünüşü dəyişən"
    const val INCOMPARABLE_TILE = "Görünüş tutuşdurulmadı"
    const val RUN_CONTENT = "run-ın öz məzmunu (sayılmır)"
    const val PICTURES =
        "Şəkillər saytın öz görüntüləridir: Pətək onlara heç nə çəkmir. Rənglər yalnız \"Fərq\" şəklindədir, kodla " +
            "hesablanıb."
    const val SUGGESTIONS = "Bu sahələr saytın səhvi deyilsə, öz profilinizdə maskalaya bilərsiniz (Pətək özü tətbiq etmir):"
    const val CALCULATION = "hesablama"
    val LEGEND =
        listOf(
            "red" to "qırmızı — sayılan fərq",
            "orange" to "narıncı — kiçik ləkə (sayılmır)",
            "blue" to "mavi — maska",
            "yellow" to "sarı — öz-özünə dəyişən",
            "green" to "yaşıl — əlavə olunan sətirlər",
            "violet" to "bənövşəyi xətt — silinən sətirlər",
        )

    /** The summary's verdict: worse, not worse but looking different (the report gate), or not worse. */
    fun verdict(comparison: RunComparison): String =
        when {
            comparison.regressed -> {
                ComparisonText.WORSE
            }

            comparison.changedLooks.isNotEmpty() -> {
                "${ComparisonText.NOT_WORSE} · görünüş dəyişib (${comparison.changedLooks.size} səhifə)"
            }

            else -> {
                ComparisonText.NOT_WORSE
            }
        }

    fun verdictTone(comparison: RunComparison): String =
        when {
            comparison.regressed -> "bad"
            comparison.changedLooks.isNotEmpty() -> "warn"
            else -> "ok"
        }

    /** What was compared and how, with the thresholds and the gate. */
    fun method(comparison: RunComparison): String {
        val t = comparison.visualThresholds ?: VisualThresholds()
        val gate =
            when (comparison.visualGate) {
                VisualGate.REPORT -> "Qapı: report — dəyişən görünüş göstərilir, pisləşmə sayılmır (--visual fail ilə sayılır)."
                VisualGate.FAIL -> "Qapı: fail — dəyişən görünüş pisləşmə sayılır."
            }
        return "Eyni addımın, səhifənin və ekranın görünüşləri tutuşdurulur. Maskalar, run-ın öz mətnləri, tarix və saat " +
            "mətnləri, başqa saytın çərçivələri və video, run içində öz-özünə dəyişən hissələr tutuşdurulmur. Rəng fərqi həddi " +
            "${decimal(t.colorDelta, 2)} (YIQ), ${t.shiftRadius} px sürüşmə sayılmır, sahə ən azı ${t.regionCells} xanadır " +
            "(${t.cell}×${t.cell} px), zolaq ən azı ${t.bandRows} px-dir. Dəyişiklik bütün nümunə cütlərində görünməlidir. $gate"
    }

    fun change(look: LookComparison): String =
        when (look.change) {
            LookChange.CHANGED -> "dəyişib"
            LookChange.UNCHANGED -> "dəyişməyib"
            LookChange.NOT_COMPARABLE -> "tutuşdurulmur"
            LookChange.ADDED -> "yeni"
            LookChange.REMOVED -> "çıxarılıb"
        }

    fun tone(
        look: LookComparison,
        gate: VisualGate,
    ): String =
        when (look.change) {
            LookChange.CHANGED -> if (gate == VisualGate.FAIL) "bad" else "warn"
            LookChange.UNCHANGED -> "ok"
            LookChange.NOT_COMPARABLE -> "muted"
            LookChange.ADDED, LookChange.REMOVED -> "info"
        }

    /** Why a look is not comparable, in the owner's words. */
    fun reason(look: LookComparison): String? =
        when (look.reason) {
            null -> {
                null
            }

            LookReason.MISSING_BEFORE -> {
                "əvvəlki run bu səhifənin görünüşünü çəkməyib"
            }

            LookReason.MISSING_NOW -> {
                "indiki run bu səhifənin görünüşünü çəkməyib"
            }

            LookReason.SURROUNDINGS -> {
                "sayt 429/503 cavabı verdi: mühitdir, sayt yox"
            }

            LookReason.OTHER_BROWSER -> {
                "brauzer və ya sistem fərqlidir (${renderers(look.before, look.after)}); bu maşında yeni baseline çəkin"
            }

            LookReason.OTHER_SCREEN -> {
                "ekran ölçüsü fərqlidir (${screen(look.before)} → ${screen(look.after)})"
            }

            LookReason.KEPT_MOVING -> {
                "səhifə öz-özünə dəyişməkdə davam etdi (sahənin ${ReportFormat.percent(look.reasonShare ?: 0.0)}-i); dəyişən " +
                    "hissəni target_profile.visual.mask ilə maskalayın"
            }

            LookReason.MOSTLY_IGNORED -> {
                "səhifənin ${ReportFormat.percent(look.reasonShare ?: 0.0)}-i tutuşdurulmadı"
            }

            LookReason.NOT_SETTLED -> {
                "səhifə yüklənib bitmədi (şəbəkə, şəkillər); fərq yüklənmə ilə izah oluna bilər"
            }

            LookReason.NO_IMAGE -> {
                "şəkil faylı tapılmadı və ya oxunmadı"
            }

            LookReason.EVIDENCE_ALTERED -> {
                "sübut faylı dəyişib: sha256 uyğun gəlmir"
            }
        }

    /** `telefon`, `planşet`, `kompüter`; a dash for the session's own screen. */
    fun device(key: String?): String =
        when (key) {
            null -> ReportFormat.NONE
            "phone" -> "telefon"
            "tablet" -> "planşet"
            "desktop" -> "kompüter"
            else -> key
        }

    /** `/qiymetler · telefon · public-look`. */
    fun title(look: LookComparison): String = "${look.key.page} · ${device(look.key.device)} · ${look.key.scenarioStep}"

    /** `dəyişib: 2 sahə (ekranın 1,4%-i), y≈388-də 120 px əlavə olunub`. */
    fun summary(look: LookComparison): String {
        val parts = mutableListOf<String>()
        if (look.countedRegions.isNotEmpty()) parts += "${look.countedRegions.size} sahə (ekranın ${share(look.changedShare)}-i)"
        look.countedBands.forEach { band ->
            parts +=
                when (band.kind) {
                    BandKind.INSERTED -> "y≈${band.y}-də ${band.height} px əlavə olunub"
                    BandKind.REMOVED -> "y≈${band.at}-də ${band.height} px silinib"
                }
        }
        if (parts.isEmpty()) {
            look.facts.filter { it.kind == LookFactKind.LANDED || it.kind == LookFactKind.STATUS }.mapTo(
                parts,
            ) { fact(look, it) }
        }
        return "${change(look)}: ${parts.joinToString(", ")}"
    }

    /** One fact of a look in the owner's words. */
    fun fact(
        look: LookComparison,
        fact: LookFact,
    ): String =
        when (fact.kind) {
            LookFactKind.LANDED -> {
                if (fact.before == look.key.page) {
                    "${look.key.page} indi ${dative(fact.after)} aparır"
                } else {
                    "${look.key.page} əvvəl ${dative(fact.before)} aparırdı, indi ${dative(fact.after)} aparır"
                }
            }

            LookFactKind.STATUS -> {
                "status ${fact.before.ifEmpty { ReportFormat.NONE }} → ${fact.after.ifEmpty { ReportFormat.NONE }}"
            }

            LookFactKind.HEIGHT -> {
                "hündürlük ${fact.before} → ${fact.after} px"
            }

            LookFactKind.FONTS -> {
                val added = names(fact.after).map { "+$it" }
                val gone = names(fact.before).map { "−$it" }
                "şriftlər: ${(added + gone).joinToString(", ")}"
            }

            LookFactKind.TESTERS -> {
                "bu addımda tester sayı fərqlidir (${fact.before} → ${fact.after})"
            }
        }

    /** The facts, then every region or band that is the run's own content. */
    fun facts(look: LookComparison): List<String> =
        look.facts.map { fact(look, it) } +
            look.regions.filter { it.runContent }.map { "${area(it.box.width, it.box.height, it.box.x, it.box.y)}: $RUN_CONTENT" } +
            look.bands.filter { it.runContent }.map { "${area(look.after?.viewportWidth ?: 0, it.height, 0, it.y)}: $RUN_CONTENT" }

    /** `Sahə 1: 375×120 @ 0,388`. */
    fun crop(
        number: Int,
        width: Int,
        height: Int,
        x: Int,
        y: Int,
    ): String = "Sahə $number: $width×$height @ $x,$y"

    private fun area(
        width: Int,
        height: Int,
        x: Int,
        y: Int,
    ) = "Sahə $width×$height @ $x,$y"

    /** `run mətni 1,2%, tarix/saat 0,3%, öz-özünə dəyişən 4,0%`: the current capture not compared, by reason. */
    fun ignored(look: LookComparison): String? {
        if (look.capturedPixels == 0L) return null
        return look.ignored.entries
            .filter { it.value > 0 }
            .joinToString(", ") { (reason, pixels) -> "${ignoredBy(reason)} ${share(pixels.toDouble() / look.capturedPixels)}" }
            .ifEmpty { null }
    }

    fun ignoredBy(reason: IgnoredBy): String =
        when (reason) {
            IgnoredBy.PROFILE -> "profil maskası"
            IgnoredBy.STEP -> "addımın maskası"
            IgnoredBy.MARKUP -> "data-petek-mask"
            IgnoredBy.RUN_TEXT -> "run mətni"
            IgnoredBy.TIME_TEXT -> "tarix/saat"
            IgnoredBy.EMBED -> "video və başqa saytın çərçivəsi"
            IgnoredBy.MOVING -> "öz-özünə dəyişən"
            IgnoredBy.PEERS -> "testerlər arasında fərqli"
        }

    /** `a01 · stp_… · 0003-visual.png · sha256 3f2a9c1b0d4e yoxlanıb · chromium 141.0; …`. */
    fun provenance(side: LookSide): String =
        listOf(
            side.agentId.value,
            side.stepId.value,
            side.relativePath.substringAfterLast('/').ifEmpty { ReportFormat.NONE },
            "sha256 ${side.sha256.take(SHORT_HASH).ifEmpty { ReportFormat.NONE }} ${if (side.verified) "yoxlanıb" else "yoxlanmayıb"}",
            side.renderer,
        ).joinToString(" · ")

    /** `2 → 1`: the captures each side used. */
    fun samples(look: LookComparison): String = "${look.before?.samples ?: 0} → ${look.after?.samples ?: 0}"

    /** A share with one decimal and a decimal comma: `1,4%`. */
    fun share(rate: Double): String = decimal(rate * PERCENT, 1) + "%"

    /** The suggested masks as the owner's profile would hold them. */
    fun suggestionsYaml(look: LookComparison): String =
        buildString {
            appendLine("target_profile:")
            appendLine("  visual:")
            appendLine("    mask:")
            look.suggestions.forEach { appendLine("      - $it") }
        }

    /** A path in the dative: `/giris-ə`, `/about-a`, `/home-yə`. */
    fun dative(word: String): String {
        val lower = word.lowercase(Locale.ROOT)
        val last = lower.lastOrNull { it in VOWELS }
        val suffix = if (last != null && last in BACK_VOWELS) "a" else "ə"
        val buffer = if (lower.lastOrNull()?.let { it in VOWELS } == true) "y" else ""
        return "$word-$buffer$suffix"
    }

    private fun names(list: String): List<String> = list.split(", ").filter { it.isNotBlank() }

    private fun screen(side: LookSide?): String = side?.let { "${it.viewportWidth}×${it.viewportHeight}" } ?: ReportFormat.NONE

    /** The parts of two renderers that differ: `chromium 140.0 → chromium 141.0`. */
    private fun renderers(
        before: LookSide?,
        after: LookSide?,
    ): String {
        val then = before?.renderer.orEmpty().split("; ")
        val now = after?.renderer.orEmpty().split("; ")
        val differing = (0 until maxOf(then.size, now.size)).filter { then.getOrNull(it) != now.getOrNull(it) }
        if (differing.isEmpty()) return "${before?.renderer} → ${after?.renderer}"
        return differing.joinToString(", ") { "${then.getOrNull(it) ?: ReportFormat.NONE} → ${now.getOrNull(it) ?: ReportFormat.NONE}" }
    }

    private fun decimal(
        value: Double,
        digits: Int,
    ): String = String.format(Locale.ROOT, "%.${digits}f", value).replace('.', ',')

    private const val SHORT_HASH = 12
    private const val PERCENT = 100.0
    private const val VOWELS = "aıoueəiöü"
    private const val BACK_VOWELS = "aıou"
}
