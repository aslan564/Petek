package az.petek.app.cli

import az.petek.app.campaign.CampaignScaler
import az.petek.app.campaign.IdentitySpecs
import az.petek.app.di.AppContainer
import az.petek.campaign.domain.Campaign
import az.petek.campaign.domain.CampaignValidationException
import az.petek.campaign.domain.DefaultCampaignValidator
import az.petek.campaign.domain.ValidationIssue
import az.petek.core.ids.RunTags
import az.petek.orchestration.domain.DefaultActorResolver
import az.petek.orchestration.domain.RunOptions
import az.petek.orchestration.domain.RunOutcome
import az.petek.orchestration.domain.RunSummary
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
import java.nio.file.Path
import java.util.Locale

/**
 * `petek run <campaign.yaml>`: one run (or `--repeat N` runs as one stability group) end to end, then the summary and
 * the report path. Exit code 0 when every run PASSED, 1 when one FAILED, 2 when one was ABORTED or nothing could
 * start (configuration, refused target, invalid campaign).
 */
class RunCommand : PetekSubcommand("run") {
    private val file by argument("campaign", help = "campaign YAML file, e.g. scenarios/kadrohr.yaml").path()
    private val repeat by option("--repeat", help = "run the campaign N times and report stability").int().restrictTo(min = 1).default(1)
    private val keepData by option("--keep-data", help = "keep the test company on the target (debugging)").flag()
    private val headful by option("--headful", help = "show the browser windows").flag()
    private val agents by option(
        "--agents",
        help = "use only N testers for a quick trial (1 admin, role and registration ratios kept)",
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
            val runner = container.campaignRunner(headless = config.browserHeadless && !headful)
            echo(
                "Running '${campaign.settings.name}' with ${campaign.settings.testers} agents against ${config.targetLabel}" +
                    (if (repeat > 1) ", $repeat times" else "") + (if (keepData) ", keeping the test data" else "") + ".",
            )
            val summaries =
                try {
                    if (repeat == 1) {
                        listOf(runner.run(campaign, RunOptions(keepData = keepData)))
                    } else {
                        container.repeatRunner(runner).repeat(campaign, repeat, keepData)
                    }
                } finally {
                    container.closeMonitor()
                }
            summaries.forEach { printSummary(it, config.logDirectory.resolve(LOG_FILE)) }
            exitCodeOf(summaries)
        }

    private fun scaled(
        container: AppContainer,
        campaign: Campaign,
        agents: Int,
    ): Campaign {
        val scaled = CampaignScaler.scale(campaign, agents)
        val issues = DefaultCampaignValidator(container.templateRenderer).validate(scaled, container.knownRunFunctions)
        if (issues.isNotEmpty()) {
            throw CampaignValidationException(issues + ValidationIssue(null, "--agents $agents is too small for this campaign"))
        }
        val spec = IdentitySpecs.of(scaled.settings, container.config.mailDomain)
        val preview = container.identityGenerator.generate(spec, RunTags.forPlan(scaled.sourceHash, scaled.settings.seed))
        CampaignScaler.uncoveredSteps(scaled, preview.identities, DefaultActorResolver()).forEach { step ->
            echo(
                "Warning: with --agents $agents no tester matches '${step.actors.raw}', so step '${step.id}' (line ${step.line}) " +
                    "will be skipped.",
                err = true,
            )
        }
        return scaled
    }

    private fun printSummary(
        summary: RunSummary,
        logFile: Path,
    ) {
        echo(
            "Run ${summary.runId}: ${summary.outcome} in ${seconds(summary.durationMs)} " +
                "(steps passed ${summary.stepsPassed}, failed ${summary.stepsFailed}; assertions failed ${summary.assertionsFailed}; " +
                "failed agents ${summary.failedAgents})",
        )
        val report = summary.reportDirectory
        if (report == null) {
            echo("  No report was written; the reason is in $logFile.", err = true)
        } else {
            echo("  Report: ${Path.of(report).resolve(HTML_REPORT)}")
        }
    }

    private fun seconds(millis: Long): String = String.format(Locale.ROOT, "%.1f s", millis / MILLIS_PER_SECOND)

    companion object {
        const val HTML_REPORT = "index.html"
        private const val LOG_FILE = "petek.log"
        private const val MILLIS_PER_SECOND = 1000.0

        /** The worst outcome decides: any ABORTED run is 2, any FAILED run is 1, else 0. */
        fun exitCodeOf(summaries: List<RunSummary>): Int =
            when {
                summaries.any { it.outcome == RunOutcome.ABORTED } -> ExitCodes.CONFIG_OR_ABORTED
                summaries.any { it.outcome == RunOutcome.FAILED } -> ExitCodes.FAILURE
                else -> ExitCodes.OK
            }
    }
}
