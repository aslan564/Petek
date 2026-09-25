package az.petek.agent.application.runs

import az.petek.agent.application.RunFunction
import az.petek.agent.domain.ActionOutcome
import az.petek.agent.domain.AgentRuntime
import az.petek.agent.domain.FailureReason
import az.petek.agent.domain.SharedRunState
import az.petek.agent.domain.StepContext
import az.petek.core.model.RegistrationMode
import az.petek.oracle.domain.TargetOracle
import az.petek.oracle.domain.TestCompany

/**
 * `register_owner` (admin; arg `company`, default [DEFAULT_COMPANY]): the owner sign-up without the LLM.
 * `/register` form -> e-mail code -> phone code when asked -> signed in -> identity check -> storage state saved.
 * With the test API available, the new company's id and code are published to [SharedRunState] for the others.
 */
internal class RegisterOwnerRunFunction(
    private val engine: RunEngine,
    private val flows: TargetFlows,
    private val oracle: TargetOracle,
    private val settings: RunFunctionSettings,
) : RunFunction {
    override val name: String = RunFunctions.REGISTER_OWNER

    override suspend fun execute(
        runtime: AgentRuntime,
        args: Map<String, String>,
        step: StepContext,
    ): ActionOutcome =
        engine.execute(name, runtime, step) {
            val identity = runtime.identity
            if (identity.registration != RegistrationMode.OWNER) {
                throw RunFailure(
                    FailureReason.MISSING_PREREQUISITE,
                    "register_owner is for the company owner; ${identity.agentId} joins by ${identity.registration.key}.",
                )
            }
            val company = args[ARG_COMPANY]?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_COMPANY
            submitSignUp(company)
            val state = flows.awaitTransition(this, PageState.OTHER)
            if (state == PageState.OTHER) {
                throw RunFailure(FailureReason.REGISTRATION_FAILED, "The sign-up form was not accepted (still on ${currentUrl()}).")
            }
            flows.signIn(this, state)
            val shown = flows.verifyIdentity(this)
            saveStorageState()
            val created = publishCompany()
            succeeded(
                "Registered ${identity.email} as owner of '$company'. $shown." + (created?.let { " Company id ${it.id}." } ?: ""),
                objectId = created?.id,
            )
        }

    private suspend fun RunTrace.submitSignUp(company: String) {
        val identity = runtime.identity
        open("register")
        if (!waitFor("register.name", settings.uiTimeout)) {
            throw RunFailure(FailureReason.REGISTRATION_FAILED, "The sign-up page shows no form (${currentUrl()}).")
        }
        fill("register.name", identity.displayName)
        fill("register.email", identity.email)
        fill("register.phone", identity.phone)
        fillPassword("register.password")
        fill("register.company", company)
        click("register.submit")
    }

    private suspend fun RunTrace.publishCompany(): TestCompany? {
        if (!oracle.isAvailable) return null
        val email = runtime.identity.email
        val company = act("look up the company owned by $email") { flows.retryOracle { oracle.companyByOwner(email) } } ?: return null
        runtime.shared.put(SharedRunState.COMPANY_ID, company.id)
        company.code?.let { runtime.shared.put(SharedRunState.COMPANY_CODE, it) }
        return company
    }

    companion object {
        const val ARG_COMPANY = "company"
        const val DEFAULT_COMPANY = "Pətək Test MMC"
    }
}
