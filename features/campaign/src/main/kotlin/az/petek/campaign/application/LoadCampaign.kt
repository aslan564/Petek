package az.petek.campaign.application

import az.petek.campaign.domain.Campaign
import az.petek.campaign.domain.CampaignValidationException
import az.petek.campaign.domain.CampaignValidator
import java.nio.file.Path

/** Port: reads a campaign file into the domain model. Syntax errors carry file line numbers. */
fun interface CampaignSource {
    fun load(path: Path): Campaign
}

/** Loads and validates a campaign; the result is safe to execute. */
class LoadCampaignUseCase(
    private val source: CampaignSource,
    private val validator: CampaignValidator,
) {
    fun execute(
        path: Path,
        knownRunFunctions: Set<String>,
    ): Campaign {
        val campaign = source.load(path)
        val issues = validator.validate(campaign, knownRunFunctions)
        if (issues.isNotEmpty()) throw CampaignValidationException(issues)
        return campaign
    }
}
