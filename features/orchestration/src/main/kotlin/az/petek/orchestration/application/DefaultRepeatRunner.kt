package az.petek.orchestration.application

import az.petek.campaign.domain.Campaign
import az.petek.core.ids.IdGenerator
import az.petek.orchestration.domain.RunOptions
import az.petek.orchestration.domain.RunSummary

/**
 * `petek run --repeat N`: runs the campaign N times one after another as one repeat group, so the report can compute
 * stability (docs/PLAN.md Faza 5). Every run gets a fresh run id, identities and test company; a failed or aborted
 * run does not stop the remaining ones, because stability needs every sample.
 *
 * @param baseOptions options applied to every run (e.g. the inactivity timeout); group, index and `keepData` are set
 *   per call.
 */
class DefaultRepeatRunner(
    private val runner: CampaignRunner,
    private val ids: IdGenerator,
    private val baseOptions: RunOptions = RunOptions(),
) : RepeatRunner {
    override suspend fun repeat(
        campaign: Campaign,
        times: Int,
        keepData: Boolean,
    ): List<RunSummary> {
        require(times >= 1) { "times must be at least 1, was $times" }
        val group = ids.correlationId().value
        return (1..times).map { index ->
            runner.run(
                campaign,
                baseOptions.copy(repeatGroup = group, repeatIndex = index, keepData = keepData),
            )
        }
    }
}
