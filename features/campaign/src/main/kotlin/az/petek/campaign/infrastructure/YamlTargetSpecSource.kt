/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.campaign.infrastructure

import az.petek.campaign.domain.OwnAccount
import az.petek.campaign.domain.SecretRef
import az.petek.campaign.domain.SignInMethod
import az.petek.campaign.domain.TargetMail
import az.petek.campaign.domain.TargetSpec
import az.petek.campaign.domain.TargetSpecException
import az.petek.campaign.domain.ValidationIssue
import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlException
import com.charleskorn.kaml.YamlNode
import java.io.IOException
import java.net.URI
import java.net.URISyntaxException
import java.nio.file.Files
import java.nio.file.Path

/**
 * Reads target profiles (`targets/<name>.yaml`, docs/PLAN.md Faza 10) with the same strict, line-numbered YAML reading
 * as campaigns:
 *
 * ```yaml
 * target:
 *   name: kadrohr
 *   url: https://staging.kadrohr.com
 *   api_url: https://api.staging.kadrohr.com
 *   production_hosts: [kadrohr.com, www.kadrohr.com]
 *   mail: {source: test-api, domain: test.kadrohr.com}
 *   test_api: {token: '${PETEK_TEST_TOKEN_KADROHR}'}
 *   sign_in: [test_company, own_accounts, self_register, anonymous]
 *   accounts:
 *     - {role: admin, email: owner@example.com, password: '${PETEK_ACC_KADROHR_ADMIN}'}
 *   profile: scenarios/kadrohr.yaml
 * ```
 *
 * A secret written out instead of a `${VARIABLE}` reference is refused. Stateless; [load] does blocking I/O.
 */
class YamlTargetSpecSource {
    fun load(path: Path): TargetSpec {
        val file = path.fileName?.toString() ?: path.toString()
        val text =
            try {
                Files.readString(path)
            } catch (e: IOException) {
                throw TargetSpecException(file, listOf(ValidationIssue(null, "cannot read it: ${e.message ?: e.javaClass.simpleName}")))
            }
        val root =
            try {
                Yaml.default.parseToYamlNode(text)
            } catch (e: YamlException) {
                throw TargetSpecException(file, listOf(ValidationIssue(e.line, "YAML syntax error: ${e.message}")))
            }
        val reader = YamlReader(collectSourceLines(root))
        val spec = read(reader, root)
        val issues = reader.issues
        if (issues.isNotEmpty() ||
            spec == null
        ) {
            throw TargetSpecException(file, issues.ifEmpty { listOf(ValidationIssue(null, "no target")) })
        }
        return spec
    }

    /** Every `*.yaml`/`*.yml` profile of [directory] (absent: none), by name; broken ones are reported together. */
    fun loadAll(directory: Path): List<TargetSpec> {
        if (!Files.isDirectory(directory)) return emptyList()
        val files =
            Files.list(directory).use { stream ->
                stream.filter { it.fileName.toString().let { name -> name.endsWith(".yaml") || name.endsWith(".yml") } }.sorted().toList()
            }
        val loaded = files.map { runCatching { load(it) } }
        val failures = loaded.mapNotNull { it.exceptionOrNull() as? TargetSpecException }
        if (failures.isNotEmpty()) {
            throw TargetSpecException(
                failures.joinToString { it.file },
                failures.flatMap { failure -> failure.issues.map { it.copy(message = "${failure.file}: ${it.message}") } },
            )
        }
        val specs = loaded.map { it.getOrThrow() }
        specs.groupBy { it.name }.filterValues { it.size > 1 }.keys.firstOrNull()?.let { duplicate ->
            throw TargetSpecException(directory.toString(), listOf(ValidationIssue(null, "two profiles are named '$duplicate'")))
        }
        return specs.sortedBy { it.name }
    }

    private fun read(
        reader: YamlReader,
        root: YamlNode,
    ): TargetSpec? {
        val top = reader.map(root, ROOT_PATH, setOf("target")) ?: return null
        val fields = top.map("target", TARGET_KEYS, required = true) ?: return null
        val name = fields.text("name", required = true)
        if (name != null &&
            !TargetSpec.NAME.matches(name)
        ) {
            reader.problem(fields.pathOf("name"), "the name is lower-case letters, digits and '-'")
        }
        val url = url(reader, fields, "url", required = true)
        val apiUrl = url(reader, fields, "api_url", required = false)
        val hosts =
            fields
                .textList("production_hosts")
                .orEmpty()
                .map { it.trim().lowercase() }
                .toSet()
        val mail = mail(fields)
        val token = fields.map("test_api", setOf("token"))?.let { secret(reader, it, "token") }
        val signIn = signIn(reader, fields)
        val accounts = accounts(reader, fields)
        val profile = fields.text("profile")
        if (name == null || url == null || !TargetSpec.NAME.matches(name)) return null
        return TargetSpec(name, url, apiUrl, hosts, mail, token, signIn ?: SignInMethod.DEFAULT_CHAIN, accounts, profile)
    }

    private fun url(
        reader: YamlReader,
        fields: YamlFields,
        key: String,
        required: Boolean,
    ): URI? {
        val text = fields.text(key, required) ?: return null
        val uri =
            try {
                URI(text.trim())
            } catch (_: URISyntaxException) {
                return reader.problem(fields.pathOf(key), "'$key' is not a valid URL")
            }
        if (uri.scheme?.lowercase() !in setOf("http", "https") || uri.host.isNullOrBlank() || uri.rawUserInfo != null) {
            return reader.problem(fields.pathOf(key), "'$key' must be an absolute http(s) URL without credentials")
        }
        return uri
    }

    private fun mail(fields: YamlFields): TargetMail {
        val mail = fields.map("mail", setOf("source", "domain", "inbox")) ?: return TargetMail()
        return TargetMail(mail.text("source"), mail.text("domain")?.lowercase(), mail.text("inbox")?.lowercase())
    }

    private fun secret(
        reader: YamlReader,
        fields: YamlFields,
        key: String,
    ): SecretRef? {
        val text = fields.text(key) ?: return null
        return SecretRef.parse(text)
            ?: reader.problem(fields.pathOf(key), "'$key' must be a \${VARIABLE} reference to .env, never the secret itself")
    }

    private fun signIn(
        reader: YamlReader,
        fields: YamlFields,
    ): List<SignInMethod>? {
        val keys = fields.textList("sign_in") ?: return null
        val methods =
            keys.mapNotNull { key ->
                SignInMethod.fromKey(key)
                    ?: reader.problem(
                        fields.pathOf("sign_in"),
                        "unknown sign_in method '$key' (allowed: ${SignInMethod.entries.joinToString { it.key }})",
                    )
            }
        if (methods.isEmpty() ||
            methods.distinct() != methods
        ) {
            return reader.problem(fields.pathOf("sign_in"), "sign_in lists each method once, at least one")
        }
        return methods
    }

    private fun accounts(
        reader: YamlReader,
        fields: YamlFields,
    ): List<OwnAccount> {
        val items = reader.list(fields["accounts"], fields.pathOf("accounts")) ?: return emptyList()
        return items.mapNotNull { item ->
            val account = reader.map(item.node, item.path, setOf("role", "email", "password", "storage_state")) ?: return@mapNotNull null
            val role = account.text("role", required = true) ?: return@mapNotNull null
            val email = account.text("email")
            val password = secret(reader, account, "password")
            val storageState = account.text("storage_state")
            if (!((email != null && password != null) || storageState != null)) {
                return@mapNotNull reader.problem(item.path, "an account needs an e-mail with a password reference, or a storage_state file")
            }
            OwnAccount(role, email, password, storageState)
        }
    }

    private companion object {
        const val ROOT_PATH = ""
        val TARGET_KEYS = setOf("name", "url", "api_url", "production_hosts", "mail", "test_api", "sign_in", "accounts", "profile")
    }
}
