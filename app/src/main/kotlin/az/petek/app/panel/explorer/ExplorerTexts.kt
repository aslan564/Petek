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

package az.petek.app.panel.explorer

import az.petek.explorer.domain.ActionKind
import az.petek.explorer.domain.ExplorationPhase
import az.petek.explorer.domain.FindingKind
import az.petek.explorer.domain.GateBlocker
import az.petek.explorer.domain.GateMap
import az.petek.explorer.domain.OtpKind
import az.petek.explorer.domain.SiteKind
import az.petek.explorer.domain.TestIdea
import az.petek.explorer.domain.TestPattern

/**
 * The Azerbaijani lines of the "Kəşfiyyat" screen's activity feed and test ideas. The explorer writes its own reasons
 * in English (they are also logged); the ones the owner meets on screen are translated here, anything unknown is
 * shown as it is.
 */
internal object ExplorerTexts {
    fun phase(phase: ExplorationPhase): String =
        when (phase) {
            ExplorationPhase.ANONYMOUS -> "Anonim gəzinti"
            ExplorationPhase.ROLE_BASED -> "Rollarla gəzinti"
            ExplorationPhase.TRIAL_TOUCH -> "Sınaq toxunuşu"
        }

    /** `anonymous` and the campaign roles in the owner's words; other role names as they are. */
    fun role(role: String): String =
        when (role) {
            "anonymous" -> "anonim"
            "admin" -> "admin"
            "manager" -> "menecer"
            "employee" -> "işçi"
            else -> role
        }

    fun kind(kind: ActionKind): String =
        when (kind) {
            ActionKind.REGISTER -> "qeydiyyat"
            ActionKind.LOGIN -> "giriş"
            ActionKind.CREATE -> "yaratma"
            ActionKind.UPDATE -> "yeniləmə"
            ActionKind.DELETE -> "silmə"
            ActionKind.APPROVE -> "təsdiq"
            ActionKind.REJECT -> "rədd"
            ActionKind.ASSIGN -> "təyin"
            ActionKind.SUBMIT -> "göndərmə"
            ActionKind.NAVIGATE -> "keçid"
            ActionKind.OTHER -> "digər"
        }

    fun finding(kind: FindingKind): String =
        when (kind) {
            FindingKind.BROKEN_LINK -> "Qırıq keçid"
            FindingKind.HTTP_ERROR -> "HTTP xətası"
            FindingKind.CONSOLE_ERROR -> "Konsol xətası"
            FindingKind.SLOW_PAGE -> "Yavaş səhifə"
            FindingKind.ACCESSIBILITY -> "Əlçatanlıq"
            FindingKind.UNEXPECTED_UI -> "Gözlənilməz UI"
        }

    /** Why the explorer skipped a phase, in the owner's words (see ExploreSiteUseCase for the English originals). */
    fun skipReason(reason: String): String =
        when {
            reason == "no logged-in sessions were given" -> {
                "daxil olmuş sessiya yoxdur"
            }

            reason == "allowWrites is false" -> {
                "“Sınaq toxunuşu” seçilməyib (kəşfiyyatçı heç nə göndərmir)"
            }

            reason.startsWith("no logged-in session was given") -> {
                "daxil olmuş sessiya yoxdur; ziyarətçinin göndərdiyi məlumat test şirkətinə aid olmur və sonra silinə bilməz"
            }

            reason.startsWith(NOT_CONFIRMED) -> {
                "hədəfin test məlumatı olduğu təsdiqlənmədi: " + reason.removePrefix(NOT_CONFIRMED)
            }

            else -> {
                reason
            }
        }

    /** A note of the explorer's summary; the ones the owner meets often are translated, others shown as they are. */
    fun note(note: String): String =
        when {
            note.startsWith(TRIAL_ALLOWED) -> "Sınaq toxunuşuna icazə verildi: " + note.removePrefix(TRIAL_ALLOWED)
            else -> note
        }

    /** The site's kind in the owner's words (Faza 17). */
    fun kind(kind: SiteKind): String =
        when (kind) {
            SiteKind.SHOP -> "Mağaza"
            SiteKind.NEWS -> "Xəbər saytı"
            SiteKind.SHOWCASE -> "Vitrin"
            SiteKind.SIGN_IN_SYSTEM -> "Giriş sistemi"
            SiteKind.OTHER -> "Digər"
        }

    /** The gate in the owner's words, blockers last and said plainly (`LINK_ONLY_SWARM.md` §7 "Dürüst dayanma"). */
    fun gate(gate: GateMap): List<String> =
        buildList {
            add("Qeydiyyat: " + (gate.register?.path ?: "tapılmadı") + "; giriş: " + (gate.login?.path ?: "tapılmadı"))
            add(if (gate.guest) "Qonaq (hesabsız) səhifələri görür." else "Hesabsız heç nə görünmür.")
            add(
                "Təsdiq: " +
                    when (gate.otp) {
                        OtpKind.EMAIL_CODE -> "e-poçt kodu"
                        OtpKind.EMAIL_LINK -> "e-poçt linki"
                        OtpKind.SMS -> "SMS kodu"
                        OtpKind.NOT_SEEN -> "qeydiyyatdan əvvəl görünmür"
                    } + if (gate.forgotPassword) "; şifrəni unutdum var." else ".",
            )
            gate.register?.unmapped?.takeIf { it.isNotEmpty() }?.let {
                add(
                    "Pətəkin hələ doldura bilmədiyi qeydiyyat sahələri: ${it.joinToString()}",
                )
            }
            gate.blockers.forEach { blocker ->
                add(
                    when (blocker) {
                        GateBlocker.CAPTCHA -> {
                            "Qapını CAPTCHA bağlayır: testerlər özü qeydiyyatdan keçə bilməz; hesab verin və ya stage-də " +
                                "CAPTCHA-nı test IP-si üçün söndürün."
                        }

                        GateBlocker.NO_GATE -> {
                            "Qeydiyyat və giriş tapılmadı: testerlər yalnız qonaq kimi gəzə bilər."
                        }

                        GateBlocker.INVITE_ONLY -> {
                            "Qeydiyyat dəvət və ya kod istəyir: hesab verin və ya dəvət yolunu təlimatda yazın."
                        }

                        GateBlocker.NO_SIGN_UP -> {
                            "Qeydiyyat yoxdur, yalnız giriş var: testerlər sizin test hesablarınızla girəcək."
                        }
                    },
                )
            }
        }

    /** One line per test idea, written for the owner; [actionName] is the site's own label of the action. */
    fun rationale(
        idea: TestIdea,
        actionName: String,
        kind: ActionKind?,
    ): String {
        val why =
            when (idea.pattern) {
                TestPattern.HAPPY_PATH -> {
                    "adi istifadə işləməlidir."
                }

                TestPattern.PERMISSION -> {
                    if (idea.roles.isEmpty()) {
                        "icazəsi olmayan rol rədd edilməlidir."
                    } else {
                        idea.roles.joinToString { role(it) } + " bu əməliyyatı görmədi; server də onlara icazə verməməlidir."
                    }
                }

                TestPattern.RACE -> {
                    "iki istifadəçi eyni anda qərar verəndə yalnız biri uğurlu olmalıdır."
                }

                TestPattern.REALTIME -> {
                    "nəticə digər istifadəçilərin ekranına canlı çatmalıdır; hamıya çatdırılma vaxtı ölçülür."
                }

                TestPattern.BOUNDARY -> {
                    "boş və çox uzun daxiletmə rədd edilməlidir."
                }

                TestPattern.IDEMPOTENCY -> {
                    val deletes = kind == ActionKind.DELETE
                    if (deletes) "iki dəfə silmək zərər verməməlidir." else "iki dəfə göndərmək iki obyekt yaratmamalıdır."
                }

                TestPattern.DIRECT_URL -> {
                    "yaradılan obyekt ünvanı yazan başqa testerə açılmamalıdır."
                }

                TestPattern.BROKEN_LINKS -> {
                    "səhifələrin bütün linkləri cavab verməlidir (4xx/5xx olmadan)."
                }

                TestPattern.CONSOLE_ERRORS -> {
                    "konsolda xəta və uğursuz sorğu olmamalıdır."
                }

                TestPattern.SLOW_ENDPOINTS -> {
                    "heç bir sorğu yavaş olmamalıdır."
                }

                TestPattern.BACK_BUTTON -> {
                    "geri düyməsi gəlinən səhifəyə qaytarmalıdır."
                }

                TestPattern.MOBILE_VIEWPORT -> {
                    "səhifələr telefon ekranına sığmalıdır."
                }

                TestPattern.SESSION_EXPIRY -> {
                    "vaxtı bitmiş sessiya daxil olmuş istifadəçini göstərməməlidir."
                }
            }
        val matched = if (idea.rationale.endsWith(MATCHES_INSTRUCTIONS)) " Təlimatınıza uyğundur." else ""
        if (idea.pattern.siteWide) return "Bütün sayt: $why$matched"
        return "'$actionName'" + (kind?.let { " (${kind(it)})" } ?: "") + ": " + why + matched
    }

    private const val TRIAL_ALLOWED = "Trial touch allowed: "
    private const val NOT_CONFIRMED = "the target is not confirmed as test data: "
    private const val MATCHES_INSTRUCTIONS = "(matches the owner's instructions)"
}
