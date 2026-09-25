/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.capacity.infrastructure

import az.petek.capacity.domain.Bytes
import az.petek.capacity.domain.HostResourceProbe
import az.petek.capacity.domain.HostResources
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.readText
import com.sun.management.OperatingSystemMXBean as SunOperatingSystemMXBean

private val logger = KotlinLogging.logger {}

/**
 * [HostResourceProbe] for the machine this JVM runs on. Construct it in the composition root with
 * `SystemHostResourceProbe()`; probing reads a few small files and takes milliseconds.
 *
 * Memory comes from Linux `/proc/meminfo`: `MemTotal`, and `MemAvailable` (what can be used without swapping, page
 * cache included, unlike "free"). Inside cgroup v2 memory limits (a container, a systemd slice) total is lowered to
 * the smallest limit on the way from this process's cgroup to the root and available to the smallest headroom under
 * any of them (see [cgroupLimit]), because the kernel stops the processes at those limits, not at the machine's. Where
 * `/proc/meminfo` is missing (macOS, Windows) the JVM's `OperatingSystemMXBean` is used: its free memory leaves out
 * the page cache, so the advice there is on the safe side. Cores are [Runtime.availableProcessors], which already
 * respects container CPU limits.
 */
class SystemHostResourceProbe internal constructor(
    private val procRoot: Path,
    private val cgroupRoot: Path,
    private val jvmMemory: () -> MemoryFigures?,
    private val cores: () -> Int,
) : HostResourceProbe {
    constructor() : this(Path.of("/proc"), Path.of("/sys/fs/cgroup"), { osBeanMemory() }, { Runtime.getRuntime().availableProcessors() })

    override suspend fun probe(): HostResources =
        withContext(Dispatchers.IO) {
            val machine =
                meminfo() ?: jvmMemory() ?: throw IllegalStateException("neither /proc/meminfo nor the JVM reports this machine's memory")
            val limited = cgroupLimit()?.let(machine::within) ?: machine
            HostResources(
                totalMemoryBytes = limited.total,
                availableMemoryBytes = limited.available.coerceIn(0, limited.total),
                cpuCores = cores().coerceAtLeast(1),
            )
        }

    /** `MemTotal` and `MemAvailable` in bytes, or null when the file or either field is missing. */
    private fun meminfo(): MemoryFigures? {
        val fields =
            readOrNull(procRoot.resolve("meminfo"))
                ?.lineSequence()
                ?.mapNotNull { MEMINFO_LINE.matchEntire(it.trim()) }
                ?.associate { it.groupValues[1] to it.groupValues[2].toLong() * Bytes.KIB }
                ?: return null
        val total = fields["MemTotal"] ?: return null
        val available = fields["MemAvailable"] ?: return null
        return MemoryFigures(total, available).takeIf { total > 0 }
    }

    /**
     * The cgroup v2 memory limits from this process's cgroup up to the root. Total is the smallest `memory.max` on the
     * way. Available is the smallest headroom of any limited level, `memory.max − (memory.current − inactive_file)`:
     * `memory.current` includes page cache, and its inactive part (`inactive_file` of `memory.stat`) is reclaimed before
     * the limit is enforced, as `docker stats` and the kubelet count it too. The two minima are taken separately,
     * because a deeper, smaller limit can have more headroom than a parent that other cgroups have nearly filled.
     * Null without cgroup v2 or without any limit.
     */
    private fun cgroupLimit(): MemoryFigures? {
        val relative =
            readOrNull(procRoot.resolve("self/cgroup"))
                ?.lineSequence()
                ?.firstOrNull { it.startsWith(CGROUP_V2_PREFIX) }
                ?.removePrefix(CGROUP_V2_PREFIX)
                ?.trim('/')
                ?: return null
        var directory = if (relative.isEmpty()) cgroupRoot else cgroupRoot.resolve(relative)
        var total: Long? = null
        var available: Long? = null
        while (directory.startsWith(cgroupRoot) && directory.isDirectory()) {
            val limit = readOrNull(directory.resolve("memory.max"))?.trim()?.toLongOrNull()
            if (limit != null) {
                val used = readOrNull(directory.resolve("memory.current"))?.trim()?.toLongOrNull() ?: 0
                val headroom = limit - (used - inactiveFile(directory)).coerceAtLeast(0)
                total = minOf(total ?: limit, limit)
                available = minOf(available ?: headroom, headroom)
            }
            if (directory == cgroupRoot) break
            directory = directory.parent ?: break
        }
        return if (total != null && available != null) MemoryFigures(total, available) else null
    }

    /** Bytes of inactive page cache charged to the cgroup in [directory] (`inactive_file` of `memory.stat`), else 0. */
    private fun inactiveFile(directory: Path): Long =
        readOrNull(directory.resolve("memory.stat"))
            ?.lineSequence()
            ?.firstOrNull { it.startsWith(INACTIVE_FILE) }
            ?.removePrefix(INACTIVE_FILE)
            ?.trim()
            ?.toLongOrNull()
            ?.coerceAtLeast(0)
            ?: 0

    private fun readOrNull(file: Path): String? =
        try {
            if (Files.isReadable(file)) file.readText() else null
        } catch (e: IOException) {
            logger.debug { "could not read $file: ${e.message}" }
            null
        }

    /** Memory in bytes: [total] and what of it is [available] now. */
    internal data class MemoryFigures(
        val total: Long,
        val available: Long,
    ) {
        /** These figures inside [limit]: neither total nor available may exceed the limit's. */
        fun within(limit: MemoryFigures): MemoryFigures = MemoryFigures(minOf(total, limit.total), minOf(available, limit.available))
    }

    private companion object {
        val MEMINFO_LINE = Regex("""(\w+):\s+(\d+)\s+kB""")
        const val CGROUP_V2_PREFIX = "0::"
        const val INACTIVE_FILE = "inactive_file "

        /** The JVM's view of physical memory (container-aware on current JDKs); free memory stands in for available. */
        fun osBeanMemory(): MemoryFigures? {
            val bean = ManagementFactory.getOperatingSystemMXBean() as? SunOperatingSystemMXBean ?: return null
            val total = bean.totalMemorySize
            return if (total > 0) MemoryFigures(total, bean.freeMemorySize) else null
        }
    }
}
