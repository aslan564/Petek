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

package az.petek.faketarget.service

/** Result of a use case: a value, or a [Failure] the web layer turns into a form error or an HTTP status. */
internal sealed interface Outcome<out T> {
    data class Ok<T>(
        val value: T,
    ) : Outcome<T>

    data class Failed(
        val failure: Failure,
    ) : Outcome<Nothing>
}

internal fun <T> T.ok(): Outcome<T> = Outcome.Ok(this)

internal fun Failure.failed(): Outcome<Nothing> = Outcome.Failed(this)

internal enum class FailureKind { INVALID, UNAUTHORIZED, FORBIDDEN, NOT_FOUND, CONFLICT }

/** Every way a use case can fail, with the Azerbaijani message the UI shows. The JSON error code is the lower-case name. */
internal enum class Failure(
    val kind: FailureKind,
    val message: String,
) {
    NAME_REQUIRED(FailureKind.INVALID, "Ad və soyadı daxil edin."),
    EMAIL_INVALID(FailureKind.INVALID, "Düzgün e-poçt ünvanı daxil edin."),
    PHONE_INVALID(FailureKind.INVALID, "Telefon nömrəsini +994501234567 formatında daxil edin."),
    PASSWORD_TOO_SHORT(FailureKind.INVALID, "Parol ən azı 8 simvol olmalıdır."),
    COMPANY_NAME_REQUIRED(FailureKind.INVALID, "Şirkətin adını daxil edin."),
    EMAIL_TAKEN(FailureKind.CONFLICT, "Bu e-poçt artıq qeydiyyatdan keçib. Daxil olun."),
    COMPANY_CODE_REQUIRED(FailureKind.INVALID, "Şirkət kodunu daxil edin."),
    UNKNOWN_COMPANY_CODE(FailureKind.NOT_FOUND, "Bu kodla şirkət tapılmadı."),
    DEPARTMENT_REQUIRED(FailureKind.INVALID, "Departament seçin."),
    UNKNOWN_DEPARTMENT(FailureKind.INVALID, "Belə departament yoxdur."),
    INVITATION_NOT_FOUND(FailureKind.NOT_FOUND, "Dəvət tapılmadı və ya etibarsızdır."),
    INVITATION_USED(FailureKind.CONFLICT, "Bu dəvət artıq istifadə olunub."),
    NO_PENDING_VERIFICATION(FailureKind.NOT_FOUND, "Bu e-poçt üçün gözləyən təsdiq yoxdur."),
    ALREADY_VERIFIED(FailureKind.CONFLICT, "Bu addım artıq tamamlanıb. Daxil olun."),
    EMAIL_NOT_VERIFIED(FailureKind.CONFLICT, "Əvvəlcə e-poçtu təsdiqləyin."),
    CODE_INVALID(FailureKind.INVALID, "Kod yanlışdır."),
    CODE_EXPIRED(FailureKind.INVALID, "Kodun vaxtı bitib. Yeni kod istəyin."),
    TOO_MANY_ATTEMPTS(FailureKind.INVALID, "Çox sayda yanlış cəhd. Yeni kod istəyin."),
    BAD_CREDENTIALS(FailureKind.UNAUTHORIZED, "E-poçt və ya parol yanlışdır."),
    NOT_LOGGED_IN(FailureKind.UNAUTHORIZED, "Davam etmək üçün daxil olun."),
    FORBIDDEN(FailureKind.FORBIDDEN, "Bu əməliyyat üçün icazəniz yoxdur."),
    TITLE_REQUIRED(FailureKind.INVALID, "Başlığı daxil edin (ən çox 200 simvol)."),
    ANNOUNCEMENT_NOT_FOUND(FailureKind.NOT_FOUND, "Elan tapılmadı."),
    TICKET_NOT_FOUND(FailureKind.NOT_FOUND, "Müraciət tapılmadı."),
    TICKET_ALREADY_DECIDED(FailureKind.CONFLICT, "Bu müraciət artıq qərarlaşdırılıb."),
    TICKET_ALREADY_IN_PROGRESS(FailureKind.CONFLICT, "Müraciət artıq icradadır."),
    ASSIGNEE_REQUIRED(FailureKind.INVALID, "İcraçını seçin."),
    UNKNOWN_ASSIGNEE(FailureKind.INVALID, "Belə istifadəçi şirkətdə yoxdur."),
    COMPANY_NOT_FOUND(FailureKind.NOT_FOUND, "Şirkət tapılmadı."),
    NOT_A_TEST_COMPANY(FailureKind.FORBIDDEN, "Şirkət test şirkəti deyil (is_test=false)."),
    INVALID_ROLE(FailureKind.INVALID, "Rol yanlışdır (admin, manager və ya employee)."),
    INVALID_REQUEST(FailureKind.INVALID, "Sorğu yanlışdır."),
    USER_NOT_FOUND(FailureKind.NOT_FOUND, "İstifadəçi tapılmadı."),
    OTP_NOT_FOUND(FailureKind.NOT_FOUND, "Bu nömrə üçün kod yoxdur."),
    ;

    val code: String get() = name.lowercase()
}
