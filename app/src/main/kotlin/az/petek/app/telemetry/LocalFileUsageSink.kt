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

package az.petek.app.telemetry

import az.petek.core.telemetry.UsageSink
import az.petek.core.time.HarnessClock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.TreeMap

/**
 * The open core's [UsageSink] (ADR-0011, `PETEK_TELEMETRY=local`): counters add up in memory and [close] appends them
 * as one JSON line to [file], which only the owner reads. Nothing leaves the machine. A process that counted nothing
 * writes nothing.
 */
class LocalFileUsageSink(
    private val file: Path,
    private val clock: HarnessClock,
) : UsageSink,
    AutoCloseable {
    private val lock = Any()
    private val counters = TreeMap<String, Long>()

    override fun count(
        name: String,
        amount: Long,
        tags: UsageSink.Tags,
    ) {
        UsageSink.requireName(name)
        require(amount >= 0) { "telemetry counters only grow, got $amount for $name" }
        val key = if (tags.values.isEmpty()) name else name + tags.values.entries.joinToString(",", "{", "}") { "${it.key}=${it.value}" }
        synchronized(lock) { counters.merge(key, amount, Long::plus) }
    }

    /** What has been counted and not yet written. */
    fun snapshot(): Map<String, Long> = synchronized(lock) { TreeMap(counters) }

    override fun close() {
        val written = synchronized(lock) { TreeMap(counters).also { counters.clear() } }
        if (written.isEmpty()) return
        val line =
            buildJsonObject {
                put("at", clock.now().wall.toString())
                putJsonObject("counters") { written.forEach { (key, value) -> put(key, value) } }
            }.toString()
        file.parent?.let { Files.createDirectories(it) }
        Files.writeString(file, line + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND)
    }
}
