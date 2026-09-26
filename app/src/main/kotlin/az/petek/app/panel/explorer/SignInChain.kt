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

package az.petek.app.panel.explorer

import az.petek.app.config.ResolvedAccount
import az.petek.app.di.AppContainer
import az.petek.app.panel.PanelTargets
import az.petek.browser.domain.BrowserSession
import az.petek.browser.domain.BrowserSessionFactory
import az.petek.browser.domain.SessionOptions
import az.petek.campaign.domain.SignInMethod
import az.petek.campaign.domain.TargetProfile
import az.petek.explorer.domain.TestTargetVerdict
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger {}

/**
 * The explorer's way in (ADR-0010): the methods of the site's target profile (`sign_in`, default
 * `test_company → own_accounts → self_register → anonymous`) are tried in order until one yields logged-in sessions.
 * Every attempt and every fallback is a line of the exploration's activity, so the owner sees how the explorer got in
 * or why it walked without an account.
 */
internal class SignInChain(
    private val container: AppContainer,
    private val methods: Map<SignInMethod, RoleSessionSource>,
) : RoleSessionSource {
    override suspend fun open(
        request: RoleSessionRequest,
        sessions: BrowserSessionFactory,
        progress: (String) -> Unit,
    ): RoleSessions {
        val order =
            container.config
                .profileFor(request.target)
                ?.spec
                ?.signIn ?: SignInMethod.DEFAULT_CHAIN
        val reasons = mutableListOf<String>()
        for (method in order) {
            if (method == SignInMethod.ANONYMOUS) break
            val source = methods[method] ?: continue
            progress("Giriş yolu sınanır: ${label(method)}.")
            val opened = source.open(request, sessions, progress)
            if (opened.sessions.isNotEmpty()) {
                progress("Giriş yolu: ${label(method)} (${opened.sessions.keys.joinToString { ExplorerTexts.role(it) }}).")
                return opened
            }
            opened.close()
            val why = opened.note ?: "sessiya alınmadı"
            reasons += "${label(method)}: $why"
            progress("${label(method)} alınmadı: $why")
        }
        val summary = reasons.joinToString(" · ").ifEmpty { "hədəf profili yalnız anonim girişə icazə verir" }
        return RoleSessions.none("Kəşfiyyat anonim davam edir. $summary")
    }

    private fun label(method: SignInMethod): String =
        when (method) {
            SignInMethod.TEST_COMPANY -> "test şirkəti (test API)"
            SignInMethod.OWN_ACCOUNTS -> "sahibin hesabları"
            SignInMethod.SELF_REGISTER -> "özü qeydiyyat"
            SignInMethod.ANONYMOUS -> "anonim"
        }
}

/**
 * Sessions from the accounts the owner gave in the site's target profile (`accounts:`, passwords as `.env`
 * references): a saved browser state file is used as is; otherwise a session saved by an earlier exploration of the
 * same (site, role) is reused while it is still signed in, else the account signs in through the profile's login form
 * and its state is saved (`<evidence>/sessions/<site>/<role>.json`, `rw-------`) for the next time. Nothing is
 * written to the site, so the trial touch stays off: these are real accounts whose data the test API cannot confirm.
 */
internal class OwnAccountRoleSessions(
    private val container: AppContainer,
    private val profiles: SetupProfileSource,
    /** The owner's accounts for a site: the panel's and the target profile's. */
    private val accountsFor: (java.net.URI) -> List<ResolvedAccount> = {
        container.config
            .profileFor(it)
            ?.accounts
            .orEmpty()
    },
    private val loginTimeout: kotlin.time.Duration = 20.seconds,
) : RoleSessionSource {
    override suspend fun open(
        request: RoleSessionRequest,
        sessions: BrowserSessionFactory,
        progress: (String) -> Unit,
    ): RoleSessions {
        val usable = accountsFor(request.target).filter { (it.email != null && it.password != null) || it.storageState != null }
        // The explorer's own account (role `explorer`) is the one it uses when the owner gave one; testers never get it.
        val accounts = usable.filter { it.role == AppContainer.EXPLORER_ROLE }.ifEmpty { usable }
        if (accounts.isEmpty()) {
            val where =
                container.config.profileFor(request.target)?.let { "targets/${it.spec.name}.yaml" } ?: "paneldə və ya hədəf profilində"
            return RoleSessions.none("sahibin hesabı verilməyib ($where)")
        }
        val site =
            container.config
                .profileFor(request.target)
                ?.spec
                ?.name
                ?: PanelTargets.site(request.target).substringAfter("://").replace(Regex("[^A-Za-z0-9]+"), "-")
        val profile = profiles.profile().profile
        val opened = LinkedHashMap<String, BrowserSession>()
        val failures = mutableListOf<String>()
        for (account in accounts.distinctBy { it.role }) {
            try {
                val session = signIn(request, site, account, profile, sessions, progress)
                if (session != null) opened[account.role] = session else failures += "${account.role}: daxil ola bilmədi"
            } catch (e: CancellationException) {
                withContext(NonCancellable) { opened.values.forEach { runCatching { it.close() } } }
                throw e
            } catch (e: Exception) {
                logger.warn(e) { "The owner's ${account.role} account could not sign in" }
                failures += "${account.role}: ${e::class.simpleName}"
            }
        }
        if (opened.isEmpty()) return RoleSessions.none(failures.joinToString("; "))
        if (failures.isNotEmpty()) progress("Bəzi hesablar daxil ola bilmədi: ${failures.joinToString("; ")}")
        return RoleSessions(
            sessions = opened,
            testCheck = { TestTargetVerdict.Refused("sahibin real hesabları: sınaq toxunuşu yalnız test API-nin təsdiqlədiyi datada") },
            note = null,
        ) { withContext(NonCancellable) { opened.values.forEach { runCatching { it.close() } } } }
    }

    private suspend fun signIn(
        request: RoleSessionRequest,
        site: String,
        account: ResolvedAccount,
        profile: TargetProfile,
        sessions: BrowserSessionFactory,
        progress: (String) -> Unit,
    ): BrowserSession? {
        val label = "explorer-${account.role}"
        account.storageState?.let { file ->
            progress("${account.role}: verilmiş sessiya faylı ilə daxil olunur.")
            return sessions.open(SessionOptions(label, request.target, storageState = Path.of(file)))
        }
        val saved = savedSession(site, account.role)
        if (Files.isRegularFile(saved)) {
            val session = sessions.open(SessionOptions(label, request.target, storageState = saved))
            if (signedIn(session, profile)) {
                progress("${account.role}: saxlanmış sessiya işləyir, yenidən giriş edilmədi.")
                return session
            }
            progress("${account.role}: saxlanmış sessiya köhnəlib, login formu ilə daxil olunur.")
            session.close()
        }
        val email = account.email ?: return null
        val password = account.password ?: return null
        val session = sessions.open(SessionOptions(label, request.target))
        try {
            session.navigate(profile.path("login"))
            session.fillSelector(profile.selector("login.email"), email)
            session.fillSelector(profile.selector("login.password"), password.reveal())
            session.clickSelector(profile.selector("login.submit"))
            session.waitForSelector(profile.selector("login.email"), 1.seconds)
            val signedIn = waitUntilSignedIn(session, profile)
            if (!signedIn) {
                session.close()
                return null
            }
            Files.createDirectories(saved.parent)
            session.saveStorageState(saved)
            runCatching { Files.setPosixFilePermissions(saved, PosixFilePermissions.fromString("rw-------")) }
            progress("${account.role}: sahibin hesabı ilə daxil olundu; sessiya növbəti kəşfiyyat üçün saxlandı.")
            return session
        } catch (e: Exception) {
            withContext(NonCancellable) { runCatching { session.close() } }
            throw e
        }
    }

    /** Signed in: the site no longer shows its login form after a moment (the form stays up when the login failed). */
    private suspend fun waitUntilSignedIn(
        session: BrowserSession,
        profile: TargetProfile,
    ): Boolean {
        val deadline = System.nanoTime() + loginTimeout.inWholeNanoseconds
        while (System.nanoTime() < deadline) {
            if (!onLoginPage(session, profile)) return true
            kotlinx.coroutines.delay(POLL)
        }
        return false
    }

    /** A saved session is still good when the home page does not send it to the login form. */
    private suspend fun signedIn(
        session: BrowserSession,
        profile: TargetProfile,
    ): Boolean {
        session.navigate(profile.path("home"))
        return !onLoginPage(session, profile)
    }

    private suspend fun onLoginPage(
        session: BrowserSession,
        profile: TargetProfile,
    ): Boolean {
        val login = profile.path("login")
        val path =
            runCatching {
                java.net
                    .URI(session.currentUrl())
                    .path
                    .orEmpty()
            }.getOrDefault("")
        return path.trimEnd('/') == login.trimEnd('/') || session.isSelectorVisible(profile.selector("login.password"))
    }

    private fun savedSession(
        site: String,
        role: String,
    ): Path =
        container.config.evidenceDir
            .resolve(SESSIONS_DIRECTORY)
            .resolve(site)
            .resolve(role.replace(Regex("[^A-Za-z0-9_-]"), "_") + ".json")

    companion object {
        const val SESSIONS_DIRECTORY = "sessions"
        private val POLL = kotlin.time.Duration.parse("250ms")
    }
}

/**
 * The explorer registers an account of its own through the site's registration flow (the `register_owner` run function,
 * the code read from the mail source) when the owner allowed writes on the configured site. The account stays on the
 * site afterwards unless the site has a test API for teardown; the activity says so.
 */
internal class SelfRegisterRoleSessions(
    private val container: AppContainer,
    private val setup: TestCompanyRoleSessions,
) : RoleSessionSource {
    override suspend fun open(
        request: RoleSessionRequest,
        sessions: BrowserSessionFactory,
        progress: (String) -> Unit,
    ): RoleSessions {
        if (!request.allowWrites) return RoleSessions.none("qeydiyyat sayta yazır; “Sınaq toxunuşu”na icazə verilməyib")
        if (!PanelTargets.sameSite(request.target, container.config.target)) {
            return RoleSessions.none("özü qeydiyyat yalnız konfiqurasiya olunmuş hədəfdə (${PanelTargets.site(container.config.target)})")
        }
        return setup.registerOnly(request, sessions, progress)
    }
}
