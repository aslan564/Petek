/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.dashboard.demo

import az.petek.dashboard.domain.DiffLineKind
import az.petek.dashboard.domain.DiffLineView

/** A unified line diff (longest common subsequence) with [context] lines around each change, as the demo shows it. */
object LineDiff {
    fun diff(
        old: String,
        new: String,
        context: Int = 3,
    ): List<DiffLineView> {
        val a = old.lines()
        val b = new.lines()
        val lcs = Array(a.size + 1) { IntArray(b.size + 1) }
        for (i in a.indices.reversed()) {
            for (j in b.indices.reversed()) {
                lcs[i][j] = if (a[i] == b[j]) lcs[i + 1][j + 1] + 1 else maxOf(lcs[i + 1][j], lcs[i][j + 1])
            }
        }
        val all = mutableListOf<DiffLineView>()
        var i = 0
        var j = 0
        while (i < a.size || j < b.size) {
            when {
                i < a.size && j < b.size && a[i] == b[j] -> all += DiffLineView(DiffLineKind.CONTEXT, a[i], ++i, ++j)
                j < b.size && (i == a.size || lcs[i][j + 1] >= lcs[i + 1][j]) -> all += DiffLineView(DiffLineKind.ADDED, b[j], null, ++j)
                else -> all += DiffLineView(DiffLineKind.REMOVED, a[i], ++i, null)
            }
        }
        return hunks(all, context)
    }

    private fun hunks(
        lines: List<DiffLineView>,
        context: Int,
    ): List<DiffLineView> {
        val keep = BooleanArray(lines.size)
        lines.forEachIndexed { index, line ->
            if (line.kind != DiffLineKind.CONTEXT) {
                for (k in maxOf(0, index - context)..minOf(lines.lastIndex, index + context)) keep[k] = true
            }
        }
        val out = mutableListOf<DiffLineView>()
        var index = 0
        while (index < lines.size) {
            if (!keep[index]) {
                index++
                continue
            }
            val start = index
            while (index < lines.size && keep[index]) index++
            val chunk = lines.subList(start, index)
            val oldStart = chunk.firstNotNullOfOrNull { it.oldNumber } ?: 0
            val newStart = chunk.firstNotNullOfOrNull { it.newNumber } ?: 0
            val header = "@@ -$oldStart,${chunk.count { it.oldNumber != null }} +$newStart,${chunk.count { it.newNumber != null }} @@"
            out += DiffLineView(DiffLineKind.HUNK, header, null, null)
            out += chunk
        }
        return out
    }
}
