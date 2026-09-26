/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.app.panel

import az.petek.app.config.EnvFile
import az.petek.app.config.EnvFileWriter
import az.petek.app.config.PetekConfig
import az.petek.campaign.domain.SecretRef
import az.petek.campaign.infrastructure.YamlTargetSpecSource
import az.petek.core.security.Secret
import az.petek.dashboard.domain.AccountRequest
import az.petek.dashboard.domain.PanelRequestException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path

class OwnerAccountsTest {
    @TempDir
    lateinit var dir: Path

    private val config by lazy {
        PetekConfig(
            target = URI("https://stage.shop.example"),
            identitySecret = Secret("owner-accounts-test-secret-123"),
            evidenceDir = dir.resolve("evidence"),
        )
    }
    private val env by lazy { dir.resolve(".env") }
    private val targets by lazy { dir.resolve("targets") }

    @Test
    fun `an account's password goes to env and the account to the site's profile, loadable next time`() {
        Files.writeString(env, "PETEK_TARGET=https://stage.shop.example\n# keep me\n")
        val accounts = OwnerAccounts(config, env, targets)

        val views = accounts.add(AccountRequest("https://stage.shop.example/app", "Admin", "owner@shop.example", "p#ss \"word\""))

        views.single().passwordVariable shouldBe "PETEK_ACC_STAGE_SHOP_EXAMPLE_ADMIN"
        views.toString() shouldNotContain "p#ss"
        EnvFile.load(env) shouldBe
            mapOf("PETEK_TARGET" to "https://stage.shop.example", "PETEK_ACC_STAGE_SHOP_EXAMPLE_ADMIN" to "p#ss \"word\"")
        Files.readString(env) shouldNotContain "keep me\n\n"
        val spec = YamlTargetSpecSource().load(targets.resolve("stage-shop-example.yaml"))
        spec.url shouldBe URI("https://stage.shop.example")
        spec.accounts.single().password shouldBe SecretRef("PETEK_ACC_STAGE_SHOP_EXAMPLE_ADMIN")
        accounts.accountsFor(URI("https://stage.shop.example")).single().password shouldBe Secret("p#ss \"word\"")
    }

    @Test
    fun `giving a role again replaces its password and its profile line, another role is added`() {
        val accounts = OwnerAccounts(config, env, targets)

        accounts.add(AccountRequest("https://stage.shop.example", "admin", "a@shop.example", "one"))
        accounts.add(AccountRequest("https://stage.shop.example", "admin", "b@shop.example", "two"))
        val views = accounts.add(AccountRequest("https://stage.shop.example", "hr", "c@shop.example", "three"))

        views.map { it.role to it.email } shouldContainExactlyInAnyOrder listOf("admin" to "b@shop.example", "hr" to "c@shop.example")
        EnvFile.load(env)["PETEK_ACC_STAGE_SHOP_EXAMPLE_ADMIN"] shouldBe "two"
        YamlTargetSpecSource()
            .load(targets.resolve("stage-shop-example.yaml"))
            .accounts
            .map { it.role to it.email } shouldContainExactlyInAnyOrder listOf("admin" to "b@shop.example", "hr" to "c@shop.example")
    }

    @Test
    fun `a bad role, e-mail or password is refused by field and nothing is written`() {
        val accounts = OwnerAccounts(config, env, targets)

        val refused =
            shouldThrow<PanelRequestException> {
                accounts.add(AccountRequest("https://stage.shop.example", "Baş admin!", "not-an-email", "line\nbreak"))
            }

        refused.problems.map { it.field } shouldContainExactlyInAnyOrder listOf("role", "email", "password")
        Files.exists(env) shouldBe false
        Files.exists(targets) shouldBe false
    }

    @Test
    fun `a production site is refused before anything is written`() {
        val accounts = OwnerAccounts(config, env, targets)

        shouldThrow<PanelRequestException> { accounts.add(AccountRequest("https://kadrohr.com", "admin", "a@b.az", "x")) }
        Files.exists(env) shouldBe false
    }

    @Test
    fun `the env writer keeps other lines and replaces an exported key`() {
        Files.writeString(env, "export PETEK_X=old\nOTHER=1\n")

        EnvFileWriter.set(env, "PETEK_X", "new")

        Files.readString(env) shouldBe "PETEK_X=\"new\"\nOTHER=1\n"
    }
}
