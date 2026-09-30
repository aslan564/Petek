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

package az.petek.app.cli

import az.petek.app.campaign.CampaignScaler
import az.petek.app.campaign.IdentitySpecs
import az.petek.app.config.MailSource
import az.petek.app.di.AppContainer
import az.petek.campaign.domain.Campaign
import az.petek.campaign.domain.CampaignValidationException
import az.petek.campaign.domain.DefaultCampaignValidator
import az.petek.campaign.domain.ValidationIssue
import az.petek.campaign.domain.VisitorRun
import az.petek.capacity.application.RecommendCapacityUseCase
import az.petek.core.ids.RunTags
import az.petek.identity.domain.Identity
import az.petek.orchestration.domain.DefaultActorResolver
import az.petek.orchestration.domain.RunOptions
import az.petek.orchestration.domain.RunOutcome
import az.petek.orchestration.domain.RunSummary
import az.petek.orchestration.domain.Waves
import az.petek.ownership.domain.OwnershipRequiredException
import az.petek.ownership.domain.OwnershipStatus
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.path
import com.github.ajalt.clikt.parameters.types.restrictTo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.Locale

/**
 * `petek run <campaign.yaml>`: one run (or `--repeat N` runs as one stability group) end to end, then the summary and
 * the report path. Exit code 0 when every run PASSED, 1 when one FAILED, 2 when one was ABORTED or nothing could
 * start (configuration, refused target, invalid campaign), 3 when a run did not pass only because some of its checks
 * could not be decided (nothing failed).
 */
class RunCommand : PetekSubcommand("run") {
    private val file by argument("campaign", help = "campaign YAML file, e.g. docs/examples/company-portal.yaml").path()
    private val repeat by option("--repeat", help = "run the campaign N times and report stability").int().restrictTo(min = 1).default(1)
    private val keepData by option("--keep-data", help = "keep the test company on the target (debugging)").flag()
    private val headful by option("--headful", help = "show the browser windows").flag()
    private val swapAccounts by option(
        "--swap-accounts",
        help = "after the main steps, let the testers that finished hand their accounts on and run the main steps again",
    ).flag()
    private val ci by option(
        "--ci",
        help =
            "CI mode: never interactive, print the JUnit XML, SARIF and HTML report paths, and add the Markdown report to " +
                "the GitHub Actions job summary when GITHUB_STEP_SUMMARY is set",
    ).flag()
    private val agents by option(
        "--testers",
        "--agents",
        help = "use N testers instead of the campaign's count, fewer or more (1 admin, role and registration ratios kept)",
        metavar = "N",
    ).int().restrictTo(min = 1)

    override fun help(context: Context): String = "Run a campaign with its tester agents and write the report."

    /** Nothing a run command fails with is a test finding: a run that could not start counts as aborted. */
    override fun exitCodeFor(error: Exception): Int = ExitCodes.CONFIG_OR_ABORTED

    override suspend fun execute(): Int =
        withContainer { container ->
            val config = container.config
            TargetGuard.requireAllowed(config.targetPolicy, config.target)
            val loaded = withContext(Dispatchers.IO) { container.campaigns.execute(session.resolve(file), container.knownRunFunctions) }
            val campaign = agents?.let { scaled(container, loaded, it) } ?: loaded
            TargetGuard.requireAllowed(config.targetPolicy, campaign.settings.target)
            // The site that was given must answer before a single tester starts (rule 12): a site that is down or
            // blocked is reported as such, never tested against something else.
            container.reachability.require(campaign.settings.target)
            // A run signs up and writes: only on a site whose owner proved it is theirs (ADR-0012), or a local one. A
            // visitor run only looks, with as many visitors as the owner chose, and needs no proof.
            val ownership = container.ownership.check(campaign.settings.target)
            if (ownership is OwnershipStatus.Unverified) {
                val problems = VisitorRun.problems(campaign)
                if (problems.isNotEmpty()) throw OwnershipRequiredException(ownership, notAVisitorRun(problems))
                if (!json) {
                    echo(
                        "${ownership.host} has not proved its ownership, so '${campaign.settings.name}' runs as a visitor run: " +
                            "${campaign.settings.testers} visitor(s) that only read; nothing is sent to the site.",
                    )
                }
                // Separate IPs only for the owner's own site (Faza 21): nobody spreads load over many addresses elsewhere.
                if (config.proxies.isNotEmpty()) {
                    echo("Warning: PETEK_PROXIES is not used here: every visitor goes out from this machine's IP.", err = true)
                }
            }
            if (swapAccounts && campaign.settings.waveSize != null) {
                echo("Warning: --swap-accounts is not done in a campaign with campaign.wave_size; the run says so too.", err = true)
            }
            warnAboutWaves(container, campaign)
            warnAboutCapacity(container, campaign)
            // Every tester's code typed by hand is for the explorer's few sessions, not for a swarm (R07).
            if (config.mailSource == MailSource.MANUAL && campaign.settings.testers > MANUAL_MAIL_TESTERS) {
                echo(
                    "Warning: PETEK_MAIL_SOURCE=manual: you type every tester's e-mail code in the panel, " +
                        "${campaign.settings.testers} of them; a test inbox (mailpit, test-api or imap) suits a swarm.",
                    err = true,
                )
            }
            // Valid, but likely not what was meant (Faza 24.15); never blocks the run.
            DefaultCampaignValidator(container.templateRenderer).warnings(campaign).forEach { echo("Warning: $it", err = true) }
            val runner = container.campaignRunner(headless = config.browserHeadless && !headful)
            if (!json) {
                echo(
                    "Running '${campaign.settings.name}' with ${campaign.settings.testers} agents against ${config.targetLabel}" +
                        (if (repeat > 1) ", $repeat times" else "") + (if (keepData) ", keeping the test data" else "") + ".",
                )
            }
            val options =
                RunOptions(keepData = keepData, swapAccounts = swapAccounts, ownSite = ownership !is OwnershipStatus.Unverified)
            // One run at a time over this evidence store: a panel of the same workspace may be running one already.
            val held = container.runLock.acquire("petek run")
            val summaries =
                try {
                    if (repeat == 1) {
                        listOf(runner.run(campaign, options))
                    } else {
                        container.repeatRunner(runner).repeat(campaign, repeat, options)
                    }
                } finally {
                    held.close()
                    container.closeMonitor()
                }
            if (json) emitJson(summariesJson(summaries)) else summaries.forEach { printSummary(it, config.logDirectory.resolve(LOG_FILE)) }
            if (ci) ciOutputs(summaries)
            exitCodeOf(summaries)
        }

    /** What could have started without the proof, and why this campaign did not qualify. */
    private fun notAVisitorRun(problems: List<String>): String =
        "Without the proof only a visitor run may start: any number of testers, all of them visitors (tenant: none, " +
            "registration guest), `run` steps that only read (${VisitorRun.READ_ONLY_FUNCTIONS.sorted().joinToString()}) " +
            "and no `do` step. This campaign is not one: ${problems.joinToString("; ")}."

    private fun scaled(
        container: AppContainer,
        campaign: Campaign,
        agents: Int,
    ): Campaign {
        val scaled = CampaignScaler.scale(campaign, agents)
        val issues = DefaultCampaignValidator(container.templateRenderer).validate(scaled, container.knownRunFunctions)
        if (issues.isNotEmpty()) {
            throw CampaignValidationException(issues + ValidationIssue(null, "--testers $agents does not fit this campaign"))
        }
        CampaignScaler.uncoveredSteps(scaled, previewIdentities(container, scaled), DefaultActorResolver()).forEach { step ->
            echo(
                "Warning: with --testers $agents no tester matches '${step.actors.raw}', so step '${step.id}' (line ${step.line}) " +
                    "will be skipped.",
                err = true,
            )
        }
        return scaled
    }

    /** The identities a run of [campaign] will plan, generated ahead for the warnings (nothing is stored). */
    private fun previewIdentities(
        container: AppContainer,
        campaign: Campaign,
    ): List<Identity> =
        container.identityGenerator
            .generate(
                IdentitySpecs.of(campaign.settings, container.config.mailDomain, container.config.mailInbox),
                RunTags.forPlan(campaign.sourceHash, campaign.settings.seed),
            ).identities

    /**
     * More testers live at once than this machine is advised to carry (`petek capacity`), said before the run starts:
     * advice only, never a limit (the owner's decision, 2026-09-25).
     */
    private suspend fun warnAboutCapacity(
        container: AppContainer,
        campaign: Campaign,
    ) {
        val live =
            Waves.plan(campaign, previewIdentities(container, campaign), DefaultActorResolver())?.maxLive ?: campaign.settings.testers
        val advice =
            RecommendCapacityUseCase(
                session.runtime.hostResources,
            ).execute(contextsPerBrowser = container.browserConfig().contextsPerBrowser)
        if (live > advice.maxTesters) {
            echo(
                "Warning: $live testers live at once is more than this machine is advised to carry (${advice.maxTesters}, " +
                    "limited by ${advice.limitingFactor.name.lowercase()}); the run goes on, but may slow down (petek capacity).",
                err = true,
            )
        }
    }

    /**
     * What `campaign.wave_size` does to the steps, said before the run starts: a race left with one racer in a wave fails
     * there; receivers in a wave without the tester that emits their event are skipped there.
     */
    private fun warnAboutWaves(
        container: AppContainer,
        campaign: Campaign,
    ) {
        val size = campaign.settings.waveSize ?: return
        val identities = previewIdentities(container, campaign)
        CampaignScaler.waitsWithoutEmitter(campaign, identities, DefaultActorResolver()).forEach { gap ->
            val never = if (gap.covered) "" else "; no wave holds both, so it is never checked (not_covered)"
            echo(
                "Warning: with campaign.wave_size $size, step '${gap.step.id}' (line ${gap.step.line}) waits for " +
                    "'${gap.step.waitFor?.event}' in wave ${gap.waves.joinToString()}, which has no tester of step " +
                    "'${gap.emitter.id}' that emits it; its receivers there are skipped$never.",
                err = true,
            )
        }
        CampaignScaler.racesSplitByWaves(campaign, identities, DefaultActorResolver()).forEach { split ->
            echo(
                "Warning: with campaign.wave_size $size, race step '${split.step.id}' (line ${split.step.line}) has a single " +
                    "racer in wave ${split.waves.joinToString()}, where it never passes (inconclusive): a race needs at least 2 " +
                    "racers in the same wave.",
                err = true,
            )
        }
    }

    private fun printSummary(
        summary: RunSummary,
        logFile: Path,
    ) {
        echo(
            "Run ${summary.runId}: ${summary.outcome} in ${seconds(summary.durationMs)} " +
                "(steps passed ${summary.stepsPassed}, failed ${summary.stepsFailed}; assertions failed ${summary.assertionsFailed}" +
                (if (summary.assertionsInconclusive > 0) ", inconclusive ${summary.assertionsInconclusive}" else "") + "; " +
                "failed agents ${summary.failedAgents})",
        )
        val report = summary.reportDirectory
        if (report == null) {
            echo("  No report was written; the reason is in $logFile.", err = true)
        } else {
            echo("  Report: ${Path.of(report).resolve(HTML_REPORT)}")
        }
    }

    /** `--ci`: the machine-readable reports of every run, and the job summary on GitHub Actions. */
    private fun ciOutputs(summaries: List<RunSummary>) {
        val directories = summaries.mapNotNull { it.reportDirectory?.let(Path::of) }
        if (!json) {
            directories.forEach { directory ->
                echo("  JUnit XML: ${directory.resolve(JUNIT_REPORT)}")
                echo("  SARIF: ${directory.resolve(SARIF_REPORT)}")
            }
        }
        val stepSummary = session.runtime.environment()[GITHUB_STEP_SUMMARY]?.takeIf { it.isNotBlank() } ?: return
        directories
            .map { it.resolve(MARKDOWN_REPORT) }
            .filter { Files.isRegularFile(it) }
            .forEach { report ->
                Files.writeString(
                    Path.of(stepSummary),
                    Files.readString(report) + "\n",
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND,
                )
            }
    }

    private fun summariesJson(summaries: List<RunSummary>) =
        buildJsonObject {
            put("exitCode", exitCodeOf(summaries))
            putJsonArray("runs") {
                summaries.forEach { summary ->
                    addJsonObject {
                        put("runId", summary.runId.value)
                        put("outcome", summary.outcome.name)
                        put("durationMs", summary.durationMs)
                        put("stepsPassed", summary.stepsPassed)
                        put("stepsFailed", summary.stepsFailed)
                        put("assertionsFailed", summary.assertionsFailed)
                        put("assertionsInconclusive", summary.assertionsInconclusive)
                        put("failedAgents", summary.failedAgents)
                        val directory = summary.reportDirectory?.let { Path.of(it).toAbsolutePath() }
                        put("report", directory?.resolve(HTML_REPORT)?.toString())
                        put("junit", directory?.resolve(JUNIT_REPORT)?.toString())
                        put("sarif", directory?.resolve(SARIF_REPORT)?.toString())
                    }
                }
            }
        }

    private fun seconds(millis: Long): String = String.format(Locale.ROOT, "%.1f s", millis / MILLIS_PER_SECOND)

    companion object {
        const val HTML_REPORT = "index.html"
        const val JUNIT_REPORT = "junit.xml"
        const val SARIF_REPORT = "findings.sarif"
        const val MARKDOWN_REPORT = "report.md"
        private const val GITHUB_STEP_SUMMARY = "GITHUB_STEP_SUMMARY"
        private const val LOG_FILE = "petek.log"
        private const val MILLIS_PER_SECOND = 1000.0

        /** More testers than the explorer's few sessions: typing every code by hand no longer suits. */
        private const val MANUAL_MAIL_TESTERS = 3

        /**
         * The worst outcome decides: any ABORTED run is 2, any FAILED run with a failure of its own is 1, a run that did
         * not pass only because checks could not be decided is 3, else 0.
         */
        fun exitCodeOf(summaries: List<RunSummary>): Int =
            when {
                summaries.any { it.outcome == RunOutcome.ABORTED } -> ExitCodes.CONFIG_OR_ABORTED
                summaries.any { it.outcome == RunOutcome.FAILED && !it.undecidedOnly } -> ExitCodes.FAILURE
                summaries.any { it.outcome == RunOutcome.FAILED } -> ExitCodes.INCONCLUSIVE
                else -> ExitCodes.OK
            }
    }
}
