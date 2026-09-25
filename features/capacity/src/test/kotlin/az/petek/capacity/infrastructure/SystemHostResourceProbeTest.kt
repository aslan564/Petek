/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.capacity.infrastructure

import az.petek.capacity.domain.Bytes.GIB
import az.petek.capacity.domain.Bytes.KIB
import az.petek.capacity.domain.Bytes.MIB
import az.petek.capacity.domain.HostResources
import az.petek.capacity.infrastructure.SystemHostResourceProbe.MemoryFigures
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.longs.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

class SystemHostResourceProbeTest {
    @TempDir
    lateinit var root: Path

    private val proc: Path get() = root.resolve("proc")
    private val cgroup: Path get() = root.resolve("cgroup")

    private fun meminfo(
        totalKib: Long,
        availableKib: Long?,
    ) {
        proc.createDirectories()
        val lines =
            listOfNotNull(
                "MemTotal:       $totalKib kB",
                "MemFree:         1024 kB",
                availableKib?.let { "MemAvailable:   $it kB" },
                "Cached:          2048 kB",
            )
        proc.resolve("meminfo").writeText(lines.joinToString("\n") + "\n")
    }

    /**
     * This process lives in cgroup [path]; [limits] maps cgroup directories (relative to the root) to max and current,
     * [inactiveFile] to the inactive page cache their `memory.stat` reports.
     */
    private fun cgroups(
        path: String,
        limits: Map<String, Pair<String, Long>>,
        inactiveFile: Map<String, Long> = emptyMap(),
    ) {
        proc.resolve("self").createDirectories()
        proc.resolve("self/cgroup").writeText("0::/$path\n")
        cgroup.resolve(path).createDirectories()
        limits.forEach { (directory, limit) ->
            val dir = cgroup.resolve(directory).createDirectories()
            dir.resolve("memory.max").writeText(limit.first + "\n")
            dir.resolve("memory.current").writeText("${limit.second}\n")
        }
        inactiveFile.forEach { (directory, bytes) ->
            cgroup
                .resolve(
                    directory,
                ).resolve("memory.stat")
                .writeText("anon 1000\nfile 5000\ninactive_anon 0\ninactive_file $bytes\nactive_file 7\n")
        }
    }

    private fun probe(
        jvm: MemoryFigures? = null,
        cores: Int = 8,
    ) = SystemHostResourceProbe(proc, cgroup, { jvm }, { cores })

    @Test
    fun `memory comes from MemTotal and MemAvailable of proc meminfo`() =
        runTest {
            meminfo(totalKib = 16 * 1024 * 1024, availableKib = 10 * 1024 * 1024)

            probe(cores = 12).probe() shouldBe HostResources(16 * GIB, 10 * GIB, 12)
        }

    @Test
    fun `the tightest cgroup memory limit on the way to the root caps total and available`() =
        runTest {
            meminfo(totalKib = 64 * 1024 * 1024, availableKib = 60 * 1024 * 1024)
            cgroups(
                "user.slice/app.scope",
                mapOf(
                    "user.slice/app.scope" to ("max" to 1 * GIB),
                    "user.slice" to ("${8 * GIB}" to 5 * GIB),
                ),
            )

            probe().probe() shouldBe HostResources(8 * GIB, 3 * GIB, 8)
        }

    @Test
    fun `a parent that others have nearly filled leaves less headroom than a deeper, smaller limit`() =
        runTest {
            meminfo(totalKib = 64 * 1024 * 1024, availableKib = 60 * 1024 * 1024)
            cgroups(
                "user.slice/app.scope",
                mapOf(
                    "user.slice/app.scope" to ("${8 * GIB}" to 1 * GIB),
                    "user.slice" to ("${16 * GIB}" to 15 * GIB),
                ),
            )

            // Total follows the smaller limit (8 GiB), available the smaller headroom (16 - 15 = 1 GiB, not 8 - 1).
            probe().probe() shouldBe HostResources(8 * GIB, 1 * GIB, 8)
        }

    @Test
    fun `inactive page cache charged to a cgroup counts as available`() =
        runTest {
            meminfo(totalKib = 64 * 1024 * 1024, availableKib = 60 * 1024 * 1024)
            cgroups("box", mapOf("box" to ("${4 * GIB}" to 3 * GIB + 512 * MIB)), inactiveFile = mapOf("box" to 2 * GIB))

            // 3.5 GiB charged, of which 2 GiB is reclaimable cache: 4 - 1.5 = 2.5 GiB can still be used.
            probe().probe() shouldBe HostResources(4 * GIB, 2 * GIB + 512 * MIB, 8)
        }

    @Test
    fun `unlimited cgroups leave the machine figures alone`() =
        runTest {
            meminfo(totalKib = 16 * 1024 * 1024, availableKib = 10 * 1024 * 1024)
            cgroups("user.slice", mapOf("user.slice" to ("max" to 4 * GIB)))

            probe().probe() shouldBe HostResources(16 * GIB, 10 * GIB, 8)
        }

    @Test
    fun `a cgroup already over its limit has nothing available`() =
        runTest {
            meminfo(totalKib = 16 * 1024 * 1024, availableKib = 10 * 1024 * 1024)
            cgroups("box", mapOf("box" to ("${2 * GIB}" to 3 * GIB)))

            probe().probe() shouldBe HostResources(2 * GIB, 0, 8)
        }

    @Test
    fun `without proc meminfo the JVM's view of memory is used`() =
        runTest {
            probe(jvm = MemoryFigures(total = 32 * GIB, available = 20 * GIB)).probe() shouldBe HostResources(32 * GIB, 20 * GIB, 8)
        }

    @Test
    fun `a meminfo without MemAvailable falls back to the JVM too`() =
        runTest {
            meminfo(totalKib = 16 * 1024 * 1024, availableKib = null)

            probe(jvm = MemoryFigures(total = 16 * GIB, available = 4 * GIB)).probe() shouldBe HostResources(16 * GIB, 4 * GIB, 8)
        }

    @Test
    fun `no source of memory figures is an error`() =
        runTest {
            shouldThrow<IllegalStateException> { probe(jvm = null).probe() }
        }

    @Test
    fun `at least one core is reported`() =
        runTest {
            meminfo(totalKib = 1024 * 1024, availableKib = 512 * 1024)

            probe(cores = 0).probe().cpuCores shouldBe 1
        }

    @Test
    fun `this machine reports plausible resources`() =
        runTest {
            val resources = SystemHostResourceProbe().probe()

            resources.totalMemoryBytes shouldBeGreaterThan 256 * 1024 * KIB
            resources.availableMemoryBytes shouldBeLessThanOrEqual resources.totalMemoryBytes
            resources.cpuCores shouldBeGreaterThanOrEqual 1
            if (Files.exists(Path.of("/proc/meminfo"))) resources.availableMemoryBytes shouldBeGreaterThan 0
        }
}
