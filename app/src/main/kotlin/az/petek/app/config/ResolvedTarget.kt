/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.app.config

import az.petek.campaign.domain.OwnAccount
import az.petek.campaign.domain.TargetSpec
import az.petek.core.security.Secret
import java.net.URI

/**
 * A target profile with its secret references resolved from `.env` and the environment (rule 10: the values live only
 * in [Secret]s, never in the profile or the database). Built by [ConfigLoader].
 */
data class ResolvedTarget(
    val spec: TargetSpec,
    val testToken: Secret?,
    val accounts: List<ResolvedAccount>,
) {
    override fun toString(): String = "ResolvedTarget(${spec.name}, ${spec.url}, testToken=${if (testToken == null) "unset" else "set"})"
}

/** An owner account with its password resolved; [storageState] is a saved browser session file instead. */
data class ResolvedAccount(
    val role: String,
    val email: String?,
    val password: Secret?,
    val storageState: String?,
    /** The name the site shows for the account (the profile's `name`). */
    val name: String? = null,
) {
    override fun toString(): String = "ResolvedAccount($role, password=${if (password == null) "unset" else "set"})"

    companion object {
        fun of(
            account: OwnAccount,
            password: Secret?,
        ) = ResolvedAccount(account.role, account.email, password, account.storageState, account.name)
    }
}

/** How a profile turns the process's settings into the settings of a run or exploration against its site. */
object TargetProfileConfig {
    /**
     * [base] for [target]: the matching profile's URL, test API, token, production hosts (added to the base's) and
     * mail settings; a site without a profile only changes the target.
     */
    fun forTarget(
        base: PetekConfig,
        target: URI,
    ): PetekConfig {
        val profile = base.profileFor(target) ?: return base.copy(target = target)
        return apply(base, profile)
    }

    fun apply(
        base: PetekConfig,
        profile: ResolvedTarget,
    ): PetekConfig {
        val spec = profile.spec
        val token = profile.testToken ?: base.testToken.takeIf { spec.testToken == null }
        // The base's test-API inbox needs a token this site may not have; its own Mailpit is the neutral fallback.
        val mailSource =
            spec.mail.source?.let(MailSource::fromKey)
                ?: base.mailSource.takeUnless { it == MailSource.TEST_API && token == null }
                ?: MailSource.MAILPIT
        val inbox = spec.mail.inbox ?: base.mailInbox.takeIf { spec.mail.domain == null }
        return base.copy(
            target = WebUrls.canonical(spec.url),
            testApiUrl = spec.apiUrl?.let(WebUrls::canonical),
            productionHosts = base.productionHosts + spec.productionHosts,
            testToken = token,
            mailSource = mailSource,
            mailDomain = inbox?.substringAfter('@') ?: spec.mail.domain ?: base.mailDomain,
            mailInbox = inbox,
            oracle = base.oracle && spec.oracle,
            oraclePaths = spec.oraclePaths.ifEmpty { base.oraclePaths },
        )
    }
}
