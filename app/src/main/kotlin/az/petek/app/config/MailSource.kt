/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.app.config

/**
 * Where the testers' verification mail is read from (`PETEK_MAIL_SOURCE`, docs/TARGET_CONTRACT.md §3): the Mailpit
 * catch-all inbox the target's SMTP points at, or the target's own test API (`GET /test/emails?to=`), which needs the
 * test token. Later sources (IMAP, a code typed in the panel) are PLAN.md Faza 10.
 */
enum class MailSource(
    val key: String,
) {
    MAILPIT("mailpit"),
    TEST_API("test-api"),
    ;

    companion object {
        fun fromKey(key: String): MailSource? = entries.firstOrNull { it.key.equals(key.trim(), ignoreCase = true) }
    }
}
