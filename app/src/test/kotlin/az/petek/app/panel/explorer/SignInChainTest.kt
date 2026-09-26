/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.app.panel.explorer

import az.petek.app.config.ResolvedAccount
import az.petek.app.config.ResolvedTarget
import az.petek.app.testing.PanelHarness
import az.petek.browser.domain.BrowserSession
import az.petek.browser.domain.BrowserSessionFactory
import az.petek.browser.domain.SessionOptions
import az.petek.browser.testing.FakeBrowserSession
import az.petek.campaign.domain.OwnAccount
import az.petek.campaign.domain.SecretRef
import az.petek.campaign.domain.SignInMethod
import az.petek.campaign.domain.TargetProfile
import az.petek.campaign.domain.TargetSpec
import az.petek.core.security.Secret
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList

class SignInChainTest {
    @TempDir
    lateinit var dir: Path

    private val open = mutableListOf<PanelHarness>()
    private val progress = CopyOnWriteArrayList<String>()
    private val sessions = CopyOnWriteArrayList<Pair<SessionOptions, FakeBrowserSession>>()

    @AfterEach
    fun close() = open.forEach { it.close() }

    private val site = URI("http://127.0.0.1:9")

    private fun harness(profile: (URI) -> ResolvedTarget?): PanelHarness =
        PanelHarness(dir, targets = listOfNotNull(profile(site))).also { open += it }

    /** Sessions whose login submit leaves the login page, as a site that accepts the password does. */
    private val factory =
        BrowserSessionFactory { options ->
            val fake = FakeBrowserSession(options.label)
            sessions += options to fake
            object : BrowserSession by fake {
                override suspend fun clickSelector(selector: String) {
                    fake.clickSelector(selector)
                    fake.url = "/dashboard"
                }
            }
        }

    private fun request(panel: PanelHarness) = RoleSessionRequest(panel.config.target, allowWrites = false, departments = emptyList())

    private fun source(
        name: String,
        answer: (RoleSessionRequest) -> RoleSessions,
        calls: MutableList<String>,
    ) = RoleSessionSource { request, _, _ ->
        calls += name
        answer(request)
    }

    @Test
    fun `methods are tried in the profile's order until one gives sessions, and each fallback is said`() =
        runBlocking<Unit> {
            val panel =
                harness {
                    ResolvedTarget(
                        TargetSpec("site", it, signIn = listOf(SignInMethod.OWN_ACCOUNTS, SignInMethod.TEST_COMPANY)),
                        null,
                        emptyList(),
                    )
                }
            val calls = mutableListOf<String>()
            val chain =
                SignInChain(
                    panel.panel.container,
                    mapOf(
                        SignInMethod.TEST_COMPANY to
                            source("test", { RoleSessions(mapOf("admin" to FakeBrowserSession()), { error("unused") }, null) }, calls),
                        SignInMethod.OWN_ACCOUNTS to source("own", { RoleSessions.none("profildə hesab verilməyib") }, calls),
                    ),
                )

            val opened = chain.open(request(panel), factory) { progress += it }

            calls shouldContainExactly listOf("own", "test")
            opened.sessions.keys shouldContainExactly listOf("admin")
            progress shouldContain "sahibin hesabları alınmadı: profildə hesab verilməyib"
            progress.last() shouldContain "Giriş yolu: test şirkəti (test API)"
        }

    @Test
    fun `when nothing gets in, the explorer walks anonymously and says why for every method`() =
        runBlocking<Unit> {
            val panel = harness { null }
            val chain =
                SignInChain(
                    panel.panel.container,
                    SignInMethod.entries.associateWith { method ->
                        RoleSessionSource { _, _, _ -> RoleSessions.none("${method.key} yox") }
                    },
                )

            val opened = chain.open(request(panel), factory) { progress += it }

            opened.sessions.keys.shouldBeEmpty()
            val note = checkNotNull(opened.note)
            note shouldContain "Kəşfiyyat anonim davam edir"
            note shouldContain "özü qeydiyyat: self_register yox"
        }

    @Test
    fun `an owner account signs in through the login form, saves its session and reuses it next time`() =
        runBlocking<Unit> {
            val panel =
                harness {
                    ResolvedTarget(
                        TargetSpec(
                            "site",
                            it,
                            accounts = listOf(OwnAccount("admin", "owner@example.com", SecretRef("ADMIN_PASSWORD"))),
                        ),
                        null,
                        listOf(ResolvedAccount("admin", "owner@example.com", Secret("s3cret-password"), null)),
                    )
                }
            val own = OwnAccountRoleSessions(panel.panel.container, { SetupProfile(TargetProfile.DEFAULT, "contract") })

            val first = own.open(request(panel), factory) { progress += it }

            first.sessions.keys shouldContainExactly listOf("admin")
            val (_, session) = sessions.single()
            session.actions.first() shouldBe "navigate /login"
            session.actions.any { it.startsWith("saveStorageState ") && it.endsWith("sessions/site/admin.json") } shouldBe true
            progress.joinToString() shouldNotContain "s3cret-password"
            first.close()

            val saved = panel.config.evidenceDir.resolve("sessions/site/admin.json")
            Files.createDirectories(saved.parent)
            Files.writeString(saved, "{}")
            sessions.clear()
            val second = own.open(request(panel), factory) { progress += it }

            second.sessions.keys shouldContainExactly listOf("admin")
            sessions.single().first.storageState shouldBe saved
            progress.last() shouldContain "saxlanmış sessiya işləyir"
        }

    @Test
    fun `a site without a profile or without accounts gives no own-account sessions`() =
        runBlocking<Unit> {
            val panel = harness { null }
            val own = OwnAccountRoleSessions(panel.panel.container, { SetupProfile(TargetProfile.DEFAULT, "contract") })

            own.open(request(panel), factory) { progress += it }.note shouldBe "bu sayt üçün hədəf profili yoxdur"
        }
}
