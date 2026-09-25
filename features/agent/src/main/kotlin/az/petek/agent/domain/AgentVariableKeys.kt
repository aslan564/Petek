/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

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
