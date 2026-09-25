/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.capacity.domain

import java.util.Locale

/** Binary byte units and a readable form for notes and the CLI (`180 MiB`, `7.5 GiB`). */
object Bytes {
    const val KIB: Long = 1024
    const val MIB: Long = 1024 * KIB
    const val GIB: Long = 1024 * MIB

    private val UNITS = listOf(GIB to "GiB", MIB to "MiB", KIB to "KiB")

    /** The largest unit that keeps the number at least 1, with one decimal unless it is whole: `1.5 GiB`, `180 MiB`. */
    fun format(bytes: Long): String {
        val (size, unit) = UNITS.firstOrNull { (size, _) -> bytes >= size } ?: return "$bytes B"
        val value = bytes.toDouble() / size
        val rounded = String.format(Locale.ROOT, "%.1f", value)
        return (if (rounded.endsWith(".0")) rounded.dropLast(2) else rounded) + " " + unit
    }
}
