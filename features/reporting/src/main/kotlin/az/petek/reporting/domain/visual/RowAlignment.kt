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

package az.petek.reporting.domain.visual

/** Rows of both captures that are not paired one to one: [beforeStart, beforeEnd) and [afterStart, afterEnd). */
data class Hunk(
    val beforeStart: Int,
    val beforeEnd: Int,
    val afterStart: Int,
    val afterEnd: Int,
) {
    /** Both sides have as many rows: they pair one to one and leave no band. */
    val even: Boolean get() = beforeEnd - beforeStart == afterEnd - afterStart
}

/**
 * Which baseline row each current row is compared with ([RowAlignment]); rows only one side has are [bands]
 * (not yet judged: [PixelDiff] decides whether they count).
 */
class Alignment internal constructor(
    val beforeHeight: Int,
    val afterHeight: Int,
    private val afterToBefore: IntArray,
    val bands: List<Band>,
    val hunks: List<Hunk>,
) {
    private val beforeToAfter =
        IntArray(beforeHeight) { -1 }.also { out ->
            afterToBefore.forEachIndexed { a, b ->
                if (b >=
                    0
                ) {
                    out[b] = a
                }
            }
        }

    /** The baseline row paired with current row [afterRow], or -1 when the row was inserted. */
    fun beforeOf(afterRow: Int): Int = if (afterRow in 0 until afterHeight) afterToBefore[afterRow] else -1

    /** The baseline row of the nearest paired current row at or above [afterRow] (0 when there is none). */
    fun beforeRowFor(afterRow: Int): Int {
        var row = afterRow.coerceAtMost(afterHeight - 1)
        while (row >= 0) {
            if (afterToBefore[row] >= 0) return afterToBefore[row]
            row--
        }
        return 0
    }

    /** The current row of the nearest paired baseline row at or above [beforeRow] (0 when there is none). */
    fun afterRowFor(beforeRow: Int): Int {
        var row = beforeRow.coerceAtMost(beforeHeight - 1)
        while (row >= 0) {
            if (beforeToAfter[row] >= 0) return beforeToAfter[row]
            row--
        }
        return 0
    }
}

/**
 * Pairs the rows of two captures so content that moved up or down is compared with itself (docs/adr/0014), in
 * O(n log n) over the rows:
 *
 * 1. Each row is hashed (64-bit FNV-1a over its pixels), with the side's own ignored pixels written as 0, so rows that
 *    differ only under a mask hash alike.
 * 2. Anchors are hashes unique in both captures and not uniform (a blank row never anchors).
 * 3. The longest increasing run of anchors keeps them in order; equal rows extend each anchor up and down.
 * 4. Rows between the matched runs are hunks. Their rows pair from the top; the surplus is an inserted band (current
 *    rows) or a removed band (baseline rows, at the current row where they were). Without any anchor, the whole
 *    captures are one hunk: they pair from the top and the height difference is a band at the bottom.
 */
object RowAlignment {
    private const val FNV_OFFSET = -0x340d631b7bdddcdbL
    private const val FNV_PRIME = 0x100000001b3L
    private const val BYTE = 0xff
    private const val BYTES = 4
    private const val BITS = 8

    fun align(
        before: Raster,
        after: Raster,
        ignoreBefore: PixelMask,
        ignoreAfter: PixelMask,
    ): Alignment {
        val beforeRows = Rows.of(before, ignoreBefore)
        val afterRows = Rows.of(after, ignoreAfter)
        val afterToBefore = IntArray(after.height) { -1 }
        val beforeMatched = BooleanArray(before.height)
        val anchors = anchors(beforeRows, afterRows)
        anchors.forEach { (b, a) ->
            afterToBefore[a] = b
            beforeMatched[b] = true
        }
        // Equal rows extend each anchor: downwards in order, then upwards in reverse, never across a paired row.
        anchors.forEach { (b0, a0) ->
            var b = b0 + 1
            var a = a0 + 1
            while (b < before.height && a < after.height && !beforeMatched[b] && afterToBefore[a] < 0 &&
                beforeRows.hash[b] == afterRows.hash[a]
            ) {
                afterToBefore[a] = b
                beforeMatched[b] = true
                b++
                a++
            }
        }
        anchors.asReversed().forEach { (b0, a0) ->
            var b = b0 - 1
            var a = a0 - 1
            while (b >= 0 && a >= 0 && !beforeMatched[b] && afterToBefore[a] < 0 && beforeRows.hash[b] == afterRows.hash[a]) {
                afterToBefore[a] = b
                beforeMatched[b] = true
                b--
                a--
            }
        }
        return hunks(before.height, after.height, afterToBefore)
    }

    /** Walks the matched rows; every gap between them is a hunk whose rows pair from the top. */
    private fun hunks(
        beforeHeight: Int,
        afterHeight: Int,
        afterToBefore: IntArray,
    ): Alignment {
        val hunks = mutableListOf<Hunk>()
        val bands = mutableListOf<Band>()
        var a = 0
        var b = 0
        while (a < afterHeight || b < beforeHeight) {
            if (a < afterHeight && afterToBefore[a] == b && b < beforeHeight) {
                a++
                b++
                continue
            }
            var aEnd = a
            while (aEnd < afterHeight && afterToBefore[aEnd] < 0) aEnd++
            val bEnd = if (aEnd < afterHeight) afterToBefore[aEnd] else beforeHeight
            val hunk = Hunk(b, bEnd, a, aEnd)
            hunks += hunk
            val beforeRows = bEnd - b
            val afterRows = aEnd - a
            val paired = minOf(beforeRows, afterRows)
            for (i in 0 until paired) afterToBefore[a + i] = b + i
            when {
                afterRows > beforeRows -> bands += Band(BandKind.INSERTED, a + paired, afterRows - paired, a + paired)
                beforeRows > afterRows -> bands += Band(BandKind.REMOVED, b + paired, beforeRows - paired, a + paired)
            }
            a = aEnd
            b = bEnd
        }
        return Alignment(beforeHeight, afterHeight, afterToBefore, bands, hunks)
    }

    /** Pairs `(before row, after row)` of unique, non-uniform hashes, the longest run increasing on both sides. */
    private fun anchors(
        before: Rows,
        after: Rows,
    ): List<Pair<Int, Int>> {
        val beforeOnce = before.unique()
        val afterOnce = after.unique()
        val candidates = mutableListOf<Pair<Int, Int>>()
        for (a in after.hash.indices) {
            if (after.uniform[a]) continue
            val b = beforeOnce[after.hash[a]] ?: continue
            if (afterOnce[after.hash[a]] == a && !before.uniform[b]) candidates += b to a
        }
        return longestIncreasing(candidates)
    }

    /** [pairs] are ordered by their after row; keeps the longest subsequence whose before rows increase too. */
    private fun longestIncreasing(pairs: List<Pair<Int, Int>>): List<Pair<Int, Int>> {
        if (pairs.isEmpty()) return emptyList()
        val tails = IntArray(pairs.size)
        val tailIndex = IntArray(pairs.size)
        val previous = IntArray(pairs.size)
        var length = 0
        pairs.forEachIndexed { i, (b, _) ->
            var low = 0
            var high = length
            while (low < high) {
                val mid = (low + high) ushr 1
                if (tails[mid] < b) low = mid + 1 else high = mid
            }
            tails[low] = b
            tailIndex[low] = i
            previous[i] = if (low > 0) tailIndex[low - 1] else -1
            if (low == length) length++
        }
        val out = ArrayList<Pair<Int, Int>>(length)
        var i = tailIndex[length - 1]
        while (i >= 0) {
            out += pairs[i]
            i = previous[i]
        }
        return out.asReversed()
    }

    /** Each row's hash and whether all its (unmasked as 0) pixels are one colour. */
    private class Rows(
        val hash: LongArray,
        val uniform: BooleanArray,
    ) {
        /** Hash to row for hashes that occur once. */
        fun unique(): Map<Long, Int> {
            val seen = HashMap<Long, Int>(hash.size * 2)
            val twice = HashSet<Long>()
            hash.forEachIndexed { row, h -> if (seen.putIfAbsent(h, row) != null) twice += h }
            twice.forEach(seen::remove)
            return seen
        }

        companion object {
            fun of(
                raster: Raster,
                ignore: PixelMask,
            ): Rows {
                val hash = LongArray(raster.height)
                val uniform = BooleanArray(raster.height)
                for (y in 0 until raster.height) {
                    var h = FNV_OFFSET
                    var first = 0
                    var same = true
                    val offset = y * raster.width
                    for (x in 0 until raster.width) {
                        var v = if (ignore.covers(x, y)) 0 else raster.argb[offset + x]
                        if (x == 0) {
                            first = v
                        } else if (v != first) {
                            same = false
                        }
                        repeat(BYTES) {
                            h = (h xor (v and BYTE).toLong()) * FNV_PRIME
                            v = v ushr BITS
                        }
                    }
                    hash[y] = h
                    uniform[y] = same
                }
                return Rows(hash, uniform)
            }
        }
    }
}
