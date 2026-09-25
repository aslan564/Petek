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
