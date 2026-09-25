package az.petek.app.campaign

import az.petek.campaign.domain.CampaignSettings
import az.petek.identity.domain.IdentitySpec

/** Maps campaign quotas to the identity generator's input the same way the runner does, so `plan` shows who runs. */
object IdentitySpecs {
    fun of(
        settings: CampaignSettings,
        mailDomain: String,
    ): IdentitySpec =
        IdentitySpec(
            testers = settings.testers,
            seed = settings.seed,
            names = settings.names,
            admins = settings.roles.admin,
            managers = settings.roles.manager,
            employees = settings.roles.employee,
            departments = settings.departments,
            inviteCount = settings.registration.invite,
            companyCodeCount = settings.registration.companyCode,
            mailDomain = mailDomain,
        )
}
