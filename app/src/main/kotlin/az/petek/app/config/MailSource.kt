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

package az.petek.app.config

/**
 * Where the testers' verification mail is read from (`PETEK_MAIL_SOURCE`, docs/TARGET_CONTRACT.md §3): the Mailpit
 * catch-all inbox the target's SMTP points at, or the target's own test API (`GET /test/emails?to=`), which needs the
 * test token; the owner's own inbox over IMAP (`imap`, Faza 16); or the owner typing the code into the panel
 * (`manual`, Faza 10: for the explorer's one to three sessions).
 */
enum class MailSource(
    val key: String,
) {
    MAILPIT("mailpit"),
    TEST_API("test-api"),
    IMAP("imap"),
    MANUAL("manual"),
    ;

    companion object {
        fun fromKey(key: String): MailSource? = entries.firstOrNull { it.key.equals(key.trim(), ignoreCase = true) }
    }
}
