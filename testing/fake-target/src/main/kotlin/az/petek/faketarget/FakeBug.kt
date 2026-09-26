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

package az.petek.faketarget

/**
 * Deliberate defects a [FakeTargetServer] can be started with, so end-to-end tests can prove that Pətək detects them.
 * With no bug enabled the fake behaves correctly.
 */
enum class FakeBug {
    /**
     * Exactly one recipient of every announcement never gets the stored notification, the live push nor a read
     * receipt (not even after opening the announcement), although the announcement's audience still lists them.
     * The victim is the first employee of the audience in e-mail order, or the first recipient in e-mail order when
     * the audience has no employee, so a campaign whose employees read the announcement always sees the loss.
     */
    DROP_NOTIFICATION_FOR_ONE_USER,

    /**
     * Approve/reject check the ticket state, then wait up to [FakeTargetConfig.raceWindow] for a concurrent decision
     * on the same ticket before writing (check-then-act without a lock), so concurrent decisions all succeed.
     */
    RACE_DOUBLE_APPROVE,

    /** Employees see the approve button and may approve tickets through the UI and the JSON API. */
    EMPLOYEE_CAN_APPROVE,

    /** "In progress" reports success, but the ticket stays `open` and no history entry is written. */
    WRONG_TICKET_STATUS,
}
