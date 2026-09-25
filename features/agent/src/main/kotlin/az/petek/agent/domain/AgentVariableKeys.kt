package az.petek.agent.domain

/** Well-known [AgentVariables] keys, readable by the model as `{vars.<key>}`. Only the harness writes them. */
object AgentVariableKeys {
    /** Newest e-mail verification code fetched for this agent. */
    const val EMAIL_CODE = "email_code"

    /** Newest phone code (OTP) read for this agent from the target's test API. */
    const val PHONE_CODE = "phone_code"

    /** `object_id` the agent reported with its last successful `done`. */
    const val LAST_OBJECT_ID = "last_object_id"
}
