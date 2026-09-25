/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.browser.infrastructure

import az.petek.browser.domain.ObservedMutation
import az.petek.core.time.HarnessTimestamp
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.net.URI
import java.time.Instant

class MutationRecorderTest {
    private fun at(nanos: Long) = HarnessTimestamp(Instant.EPOCH.plusNanos(nanos), nanos)

    private val start = at(0)

    private fun recorder(
        target: String = "https://staging.kadrohr.test",
        capacity: Int = 500,
    ) = MutationRecorder(URI(target), capacity)

    @Test
    fun `mutating requests to the target keep method path status and time`() {
        val recorder = recorder()

        recorder.responded("POST", "https://staging.kadrohr.test/tickets/t2/approve", 303, at(5))
        recorder.responded("put", "https://staging.kadrohr.test/api/tickets/t2", 200, at(6))
        recorder.responded("PATCH", "https://staging.kadrohr.test/api/tickets/t2", 422, at(7))
        recorder.responded("DELETE", "https://staging.kadrohr.test/api/tickets/t2", 409, at(8))

        recorder.since(start) shouldContainExactly
            listOf(
                ObservedMutation("POST", "/tickets/t2/approve", 303, at(5)),
                ObservedMutation("PUT", "/api/tickets/t2", 200, at(6)),
                ObservedMutation("PATCH", "/api/tickets/t2", 422, at(7)),
                ObservedMutation("DELETE", "/api/tickets/t2", 409, at(8)),
            )
    }

    @Test
    fun `reads and other methods are not mutations`() {
        val recorder = recorder()

        listOf("GET", "HEAD", "OPTIONS").forEach { recorder.responded(it, "https://staging.kadrohr.test/tickets", 200, at(1)) }

        recorder.since(start).shouldBeEmpty()
    }

    @Test
    fun `requests to any other origin are ignored`() {
        val recorder = recorder()

        listOf(
            "https://analytics.example.test/collect",
            "https://api.staging.kadrohr.test/tickets/t2/approve",
            "http://staging.kadrohr.test/tickets/t2/approve",
            "https://staging.kadrohr.test:8443/tickets/t2/approve",
            "not a url at all",
            "/relative/only",
        ).forEach { recorder.responded("POST", it, 200, at(1)) }

        recorder.since(start).shouldBeEmpty()
    }

    @Test
    fun `the origin compares hosts case-insensitively and default ports as explicit ones`() {
        val recorder = recorder("http://127.0.0.1:8080/app/")

        recorder.responded("POST", "http://127.0.0.1:8080/a", 201, at(1))
        recorder.responded("POST", "HTTP://127.0.0.1:8080/b", 201, at(2))
        val https = recorder("https://Kadrohr.test")
        https.responded("POST", "https://kadrohr.test:443/c", 200, at(3))

        recorder.since(start).map { it.path } shouldContainExactly listOf("/a", "/b")
        https.since(start).map { it.path } shouldContainExactly listOf("/c")
    }

    @Test
    fun `query strings and fragments never reach the recorded path`() {
        val recorder = recorder()

        recorder.responded("POST", "https://staging.kadrohr.test/join?token=secret#top", 303, at(1))
        recorder.responded("POST", "https://staging.kadrohr.test?x=1", 200, at(2))

        recorder.since(start).map { it.path } shouldContainExactly listOf("/join", "/")
    }

    @Test
    fun `since keeps the answers seen at or after the given harness time`() {
        val recorder = recorder()
        (1L..4L).forEach { recorder.responded("POST", "https://staging.kadrohr.test/r$it", 200, at(it * 10)) }

        recorder.since(at(20)).map { it.path } shouldContainExactly listOf("/r2", "/r3", "/r4")
        recorder.since(at(41)).shouldBeEmpty()
        recorder.since(at(0)).map { it.path } shouldContainExactly listOf("/r1", "/r2", "/r3", "/r4")
    }

    @Test
    fun `only the latest mutations are kept`() {
        val recorder = recorder(capacity = 3)
        (1L..5L).forEach { recorder.responded("POST", "https://staging.kadrohr.test/r$it", 200, at(it)) }

        recorder.since(start).map { it.path } shouldContainExactly listOf("/r3", "/r4", "/r5")
    }

    @Test
    fun `a target without a usable origin records nothing`() {
        val recorder = recorder("/relative")

        recorder.responded("POST", "https://staging.kadrohr.test/x", 200, at(1))

        recorder.since(start).shouldBeEmpty()
    }

    @Test
    fun `capacity must be positive`() {
        shouldThrow<IllegalArgumentException> { recorder(capacity = 0) }.message shouldBe "capacity must be at least 1, was 0"
    }

    @Test
    fun `a mutation describes itself as method path and status`() {
        ObservedMutation("POST", "/tickets/t2/approve", 409, start).describe() shouldBe "POST /tickets/t2/approve -> 409"
    }
}
