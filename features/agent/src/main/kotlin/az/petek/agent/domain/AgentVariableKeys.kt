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
