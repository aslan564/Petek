/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.core.security

/** Wraps sensitive text (passwords, tokens) so it never leaks through `toString`, logs or data class printing. */
class Secret(
    private val value: String,
) {
    fun reveal(): String = value

    val isBlank: Boolean get() = value.isBlank()

    override fun toString(): String = "Secret(***)"

    override fun equals(other: Any?): Boolean = other is Secret && other.value == value

    override fun hashCode(): Int = value.hashCode()
}
