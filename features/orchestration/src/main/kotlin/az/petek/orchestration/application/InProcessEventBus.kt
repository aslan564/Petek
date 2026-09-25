/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.orchestration.application

import az.petek.core.ids.AgentId
import az.petek.core.ids.IdGenerator
import az.petek.core.time.HarnessClock
import az.petek.orchestration.domain.EventBus
import az.petek.orchestration.domain.PublishedEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration

/**
 * The MVP [EventBus]: one per run, in memory, retaining every event until the run ends.
 *
 * Publishing is serialized by a [Mutex] so that the event id, the harness timestamp t0 and the sequence number are
 * taken in the same order (a later sequence never has an earlier t0). Waiters observe a [MutableStateFlow] of the
 * newest event per name, so [await] suspends without polling and a waiter that arrives after the publish returns
 * immediately. Because only the newest event per name matters to `wait_for`, older events are kept in [history]
 * for diagnostics but never block a waiter.
 */
class InProcessEventBus(
    private val clock: HarnessClock,
    private val ids: IdGenerator,
) : EventBus {
    private val publishLock = Mutex()
    private val state = MutableStateFlow(BusState.EMPTY)

    override suspend fun publish(
        name: String,
        objectId: String?,
        emitter: AgentId,
    ): PublishedEvent =
        publishLock.withLock {
            val current = state.value
            val event =
                PublishedEvent(
                    eventId = ids.eventId(),
                    name = name,
                    objectId = objectId,
                    emitter = emitter,
                    t0 = clock.now(),
                    sequence = current.lastSequence + 1,
                )
            state.value = current.with(event)
            event
        }

    override suspend fun await(
        name: String,
        afterSequence: Long,
        timeout: Duration,
    ): PublishedEvent? {
        state.value.match(name, afterSequence)?.let { return it }
        if (!timeout.isPositive()) return null
        return withTimeoutOrNull(timeout) {
            state.first { it.match(name, afterSequence) != null }.match(name, afterSequence)
        }
    }

    override fun latest(name: String): PublishedEvent? = state.value.latestByName[name]

    override fun latestAny(): PublishedEvent? = state.value.history.lastOrNull()

    /** Every event published so far, in publish (= sequence) order. */
    fun history(): List<PublishedEvent> = state.value.history

    private data class BusState(
        val latestByName: Map<String, PublishedEvent>,
        val history: List<PublishedEvent>,
    ) {
        val lastSequence: Long get() = history.lastOrNull()?.sequence ?: 0L

        fun with(event: PublishedEvent): BusState = BusState(latestByName + (event.name to event), history + event)

        fun match(
            name: String,
            afterSequence: Long,
        ): PublishedEvent? = latestByName[name]?.takeIf { it.sequence > afterSequence }

        companion object {
            val EMPTY = BusState(emptyMap(), emptyList())
        }
    }
}
