package az.petek.agent.application.runs

import az.petek.agent.application.RunFunction
import az.petek.agent.domain.ActionOutcome
import az.petek.agent.domain.AgentRuntime
import az.petek.agent.domain.FailureReason
import az.petek.agent.domain.SharedRunState
import az.petek.agent.domain.StepContext
import az.petek.core.model.RegistrationMode
import az.petek.oracle.domain.OracleException
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
            val published = publishCompany()
            succeeded("Registered ${identity.email} as owner of '$company'. $shown.${published.note}", objectId = published.company?.id)
        }

    /** What the test API said about the new company; [note] is appended to the summary. */
    private class Publication(
        val company: TestCompany?,
        val note: String,
    )

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

    /**
     * Publishes the new company for the other testers. The sign-up itself already succeeded at this point, so a test
     * API that lags or fails does not fail it: the failed lookup is recorded, the summary says so, and `seed_company`
     * looks the company up again.
     */
    private suspend fun RunTrace.publishCompany(): Publication {
        if (!oracle.isAvailable) return Publication(null, "")
        val email = runtime.identity.email
        val company =
            try {
                lookup("look up the company owned by $email") { flows.retryOracle { oracle.companyByOwner(email) } }
            } catch (e: OracleException) {
                return Publication(null, " The company was not published: the test API failed (${e.message}).")
            } ?: return Publication(null, " The company was not published: the test API does not know it yet.")
        runtime.shared.put(SharedRunState.COMPANY_ID, company.id)
        company.code?.let { runtime.shared.put(SharedRunState.COMPANY_CODE, it) }
        return Publication(company, " Company id ${company.id}.")
    }

    companion object {
        const val ARG_COMPANY = "company"
        const val DEFAULT_COMPANY = "Pətək Test MMC"
    }
}
