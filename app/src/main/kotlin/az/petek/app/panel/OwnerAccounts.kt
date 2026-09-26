/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.app.panel

import az.petek.app.config.EnvFileWriter
import az.petek.app.config.PetekConfig
import az.petek.app.config.ResolvedAccount
import az.petek.campaign.infrastructure.YamlTargetSpecSource
import az.petek.core.security.Secret
import az.petek.dashboard.domain.AccountRequest
import az.petek.dashboard.domain.AccountView
import az.petek.dashboard.domain.FieldProblem
import az.petek.dashboard.domain.PanelRequestException
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The owner's accounts for the explorer (bring-your-own accounts, Faza 10): those of the sites' target profiles and those
 * added in the panel. Adding one writes the password to [envFile] as `PETEK_ACC_<SITE>_<ROLE>` and the account, with
 * that `${VARIABLE}` reference, to the site's `targets/<site>.yaml` (created when the site has none), so the next start
 * knows it too; the database never sees it (rule 10).
 */
internal class OwnerAccounts(
    private val config: PetekConfig,
    private val envFile: Path,
    private val targetsDir: Path,
) {
    private val added = CopyOnWriteArrayList<Pair<String, ResolvedAccount>>()

    fun views(): List<AccountView> {
        val fromProfiles =
            config.targets.flatMap { target ->
                target.spec.accounts.map { AccountView(target.spec.name, it.role, it.email, it.password?.variable) }
            }
        val fromPanel = added.map { (site, account) -> AccountView(site, account.role, account.email, variable(site, account.role)) }
        return (fromProfiles + fromPanel).distinctBy { it.site to it.role }
    }

    /** Accounts for [site]: the panel's own first (the latest the owner gave), then the profile's. */
    fun accountsFor(site: URI): List<ResolvedAccount> {
        val name = siteName(site)
        return added.filter { it.first == name }.map { it.second }.reversed() + config.profileFor(site)?.accounts.orEmpty()
    }

    fun add(request: AccountRequest): List<AccountView> {
        val target = PanelTargets.allowed(request.target, config.targetPolicy, TARGET)
        val role = request.role.trim().lowercase()
        val email = request.email.trim()
        val problems = mutableListOf<FieldProblem>()
        if (!ROLE.matches(role)) problems += FieldProblem(ROLE_FIELD, "Rol kiçik hərf, rəqəm, '-' və '_' ilə 1–32 simvoldur (məs. admin).")
        if (!EMAIL.matches(email)) problems += FieldProblem(EMAIL_FIELD, "E-poçt ünvanı düzgün deyil.")
        if (request.password.isEmpty() || request.password.length > MAX_PASSWORD || request.password.any(Char::isISOControl)) {
            problems += FieldProblem(PASSWORD_FIELD, "Parol 1–$MAX_PASSWORD simvol olmalı, sətir keçidi olmamalıdır.")
        }
        if (problems.isNotEmpty()) throw PanelRequestException(problems)
        val site = siteName(target)
        val variable = variable(site, role)
        writeProfile(site, target, role, email, variable)
        EnvFileWriter.set(envFile, variable, request.password)
        added.removeIf { it.first == site && it.second.role == role }
        added += site to ResolvedAccount(role, email, Secret(request.password), null)
        return views()
    }

    /** The profile's name for [site], else a name made from its host (`staging.shop.az` → `staging-shop-az`). */
    private fun siteName(site: URI): String =
        config.profileFor(site)?.spec?.name
            ?: site.host
                .orEmpty()
                .lowercase()
                .replace(Regex("[^a-z0-9]+"), "-")
                .trim('-')
                .take(MAX_NAME)
                .ifEmpty { "site" }

    private fun variable(
        site: String,
        role: String,
    ): String = "PETEK_ACC_" + (site + "_" + role).uppercase().replace(Regex("[^A-Z0-9]+"), "_")

    /** Adds the account line to `targets/<site>.yaml`, or creates a minimal profile for the site. */
    private fun writeProfile(
        site: String,
        target: URI,
        role: String,
        email: String,
        variable: String,
    ) {
        Files.createDirectories(targetsDir)
        val file = profileFile(site) ?: targetsDir.resolve("$site.yaml")
        val before = if (Files.exists(file)) Files.readString(file) else null
        patchProfile(file, target, site, role, email, variable)
        // The profile is patched as text to keep the owner's comments; it must still load, with the account in it,
        // or the owner's file is put back as it was and nothing is added.
        val loaded = runCatching { YamlTargetSpecSource().load(file) }.getOrNull()
        if (loaded == null || loaded.accounts.none { it.role == role && it.email == email }) {
            if (before == null) Files.deleteIfExists(file) else Files.writeString(file, before)
            throw PanelRequestException(
                listOf(FieldProblem(ROLE_FIELD, "${file.fileName} profilinə hesab əlavə edilə bilmədi; faylı əl ilə yoxlayın.")),
            )
        }
    }

    private fun patchProfile(
        file: Path,
        target: URI,
        site: String,
        role: String,
        email: String,
        variable: String,
    ) {
        val entry = "    - {role: $role, email: '$email', password: '\${$variable}'}"
        if (!Files.exists(file)) {
            val url = URI(target.scheme, null, target.host, target.port, null, null, null)
            val header = "# Written by the Pətək panel (\"Hesablar\"); passwords stay in .env."
            Files.writeString(file, "$header\ntarget:\n  name: $site\n  url: $url\n  accounts:\n$entry\n")
            return
        }
        val lines = Files.readAllLines(file).toMutableList()
        val existing = lines.indexOfFirst { it.trimStart().startsWith("- {role: $role,") }
        if (existing >= 0) {
            lines[existing] = entry
        } else {
            val header = lines.indexOfFirst { it.trimEnd() == "  accounts:" }
            if (header >= 0) lines.add(header + 1, entry) else lines += listOf("  accounts:", entry)
        }
        Files.writeString(file, lines.joinToString("\n", postfix = "\n"))
    }

    /** The profile file whose `name:` is [site], whatever the file is called. */
    private fun profileFile(site: String): Path? {
        val named = Regex("(?m)^\\s*name:\\s*['\"]?${Regex.escape(site)}['\"]?\\s*$")
        return Files.list(targetsDir).use { files ->
            files
                .filter { it.fileName.toString().endsWith(".yaml") || it.fileName.toString().endsWith(".yml") }
                .toList()
                .sorted()
                .firstOrNull { named.containsMatchIn(Files.readString(it)) }
        }
    }

    companion object {
        const val TARGET = "target"
        const val ROLE_FIELD = "role"
        const val EMAIL_FIELD = "email"
        const val PASSWORD_FIELD = "password"
        private const val MAX_PASSWORD = 256
        private const val MAX_NAME = 60
        private val ROLE = Regex("[a-z0-9_-]{1,32}")
        private val EMAIL = Regex("[^\\s@'\"{}]+@[^\\s@'\"{}]+\\.[^\\s@'\"{}]+")
    }
}
