/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.reporting.domain

import az.petek.evidence.domain.StepKind
import az.petek.evidence.domain.StepStatus
import az.petek.reporting.ReportTestData.step
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class FailureKeysTest {
    @Test
    fun `a key leading the detail is found`() {
        FailureKeys.find("mail_timeout: no verification e-mail within 60s") shouldBe "mail_timeout"
    }

    @Test
    fun `a key inside the detail is found`() {
        FailureKeys.find("agent stopped (loop_detected) after 3 repeats") shouldBe "loop_detected"
    }

    @Test
    fun `the first key wins when several are present`() {
        FailureKeys.find("otp_rejected, then login_failed") shouldBe "otp_rejected"
    }

    @Test
    fun `a key never matches inside a longer word`() {
        FailureKeys.find("mail_timeout") shouldBe "mail_timeout"
        FailureKeys.find("TimeoutError: waiting for selector").shouldBeNull()
        FailureKeys.find("step_limits were raised").shouldBeNull()
    }

    @Test
    fun `an unreachable test inbox has its own key, distinct from a mail timeout`() {
        FailureKeys.find("mail_unavailable: Test inbox unreachable: Mailpit at http://127.0.0.1:8025") shouldBe
            FailureKeys.MAIL_UNAVAILABLE
        FailureKeys.find("agent stopped (mail_unavailable) after 60s") shouldBe "mail_unavailable"
        FailureKeys.of(step("join", "a02", StepStatus.ERROR, detail = "mail_unavailable: Mailpit is down")) shouldBe
            "mail_unavailable"
    }

    @Test
    fun `the key of an agent loop outcome wins over words of the observation before it`() {
        val detail =
            "ERROR: Test inbox unreachable: Mailpit at http://127.0.0.1:8025: search failed (HttpRequestTimeoutException: " +
                "Request timeout has expired) | outcome: ERROR mail_unavailable: Test inbox unreachable: Mailpit at " +
                "http://127.0.0.1:8025: search failed (HttpRequestTimeoutException: Request timeout has expired)"
        FailureKeys.find(detail) shouldBe FailureKeys.MAIL_UNAVAILABLE
        FailureKeys.find("ERROR: Timeout 30000ms exceeded | outcome: FAILED browser_error: 3 failed actions in a row") shouldBe
            "browser_error"
        FailureKeys.find("Stopped after 40 decisions | outcome: FAILED timeout: Task not finished within 10m") shouldBe "timeout"
        FailureKeys.find("Finished: done | outcome: SUCCEEDED: Elan yaradıldı").shouldBeNull()
    }

    @Test
    fun `only environment problems carry an environment explanation`() {
        FailureKeys.environmentProblem(FailureKeys.MAIL_UNAVAILABLE) shouldBe "test inbox unreachable"
        FailureKeys.environmentProblem(FailureKeys.MAIL_TIMEOUT).shouldBeNull()
        FailureKeys.environmentProblem("otp_rejected").shouldBeNull()
        FailureKeys.environmentProblem("quota_exceeded").shouldBeNull()
    }

    @Test
    fun `a plain timeout is still a key`() {
        FailureKeys.find("Navigation timeout of 30000 ms exceeded") shouldBe "timeout"
    }

    @Test
    fun `an unknown snake case key leading the detail is recognised`() {
        FailureKeys.find("quota_exceeded: 429 from target") shouldBe "quota_exceeded"
        FailureKeys.find("[captcha_shown]: cannot continue") shouldBe "captcha_shown"
    }

    @Test
    fun `a leading key wins over a known key in the free text after it`() {
        FailureKeys.find("otp_expired: timeout while waiting for a new code") shouldBe "otp_expired"
        FailureKeys.find("login_failed: session timeout") shouldBe "login_failed"
    }

    @Test
    fun `free text without a key has none`() {
        FailureKeys.find("element not found").shouldBeNull()
        FailureKeys.find("note: something").shouldBeNull()
        FailureKeys.find("").shouldBeNull()
        FailureKeys.find(null).shouldBeNull()
    }

    @Test
    fun `completed steps have no failure key even when their detail mentions one`() {
        FailureKeys.of(step("join", "a02", StepStatus.PASSED, detail = "mail_timeout on first try")).shouldBeNull()
        FailureKeys.of(step("join", "a02", StepStatus.SKIPPED, detail = "mail_timeout")).shouldBeNull()
    }

    @Test
    fun `failed and errored steps report the key of their detail`() {
        FailureKeys.of(step("join", "a02", StepStatus.FAILED, detail = "otp_rejected")) shouldBe "otp_rejected"
        FailureKeys.of(step("join", "a02", StepStatus.ERROR, detail = "browser_error: crashed")) shouldBe "browser_error"
        FailureKeys.of(step("join", "a02", StepStatus.FAILED, detail = "unclear")).shouldBeNull()
    }

    @Test
    fun `a blocked step is blocked unless it names a more specific key`() {
        FailureKeys.of(step("join", "a02", StepStatus.BLOCKED, detail = "no progress for 120s")) shouldBe FailureKeys.BLOCKED
        FailureKeys.of(step("join", "a02", StepStatus.BLOCKED, detail = null)) shouldBe FailureKeys.BLOCKED
        FailureKeys.of(step("join", "a02", StepStatus.BLOCKED, detail = "mail_timeout while blocked")) shouldBe "mail_timeout"
    }

    @Test
    fun `a blocked action the target refused on purpose is an expected refusal and no failure`() {
        val refused = step("forbidden", "a12", StepStatus.BLOCKED, detail = "permission_denied: no approve button for an employee")

        FailureKeys.isExpectedRefusal(refused) shouldBe true
        FailureKeys.isFailure(refused) shouldBe false
        FailureKeys.of(refused).shouldBeNull()
    }

    @Test
    fun `the refusal key is also read from the action when the detail names none`() {
        val refused = step("forbidden", "a12", StepStatus.BLOCKED, detail = null, action = "report_problem permission_denied")

        FailureKeys.isExpectedRefusal(refused) shouldBe true
    }

    @Test
    fun `permission denied is only expected as a blocked outcome`() {
        val failed = step("forbidden", "a12", StepStatus.FAILED, StepKind.DO, detail = "permission_denied: manager could not approve")
        val stuck = step("forbidden", "a12", StepStatus.BLOCKED, detail = "no progress for 120s", action = "click [4] \"Təsdiqlə\"")

        FailureKeys.isFailure(failed) shouldBe true
        FailureKeys.of(failed) shouldBe FailureKeys.PERMISSION_DENIED
        FailureKeys.isFailure(stuck) shouldBe true
        FailureKeys.of(stuck) shouldBe FailureKeys.BLOCKED
    }
}
