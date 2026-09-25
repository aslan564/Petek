/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.app.panel

/**
 * Keeps tester contact data out of the panel's views (the PanelBackend contract): evidence texts such as oracle paths
 * and step details name testers by e-mail (`/test/tickets/latest?by=eli.a07@test.kadrohr.com`); in a view only the
 * domain stays, so the owner still sees which system was asked.
 */
internal object Contacts {
    private val EMAIL = Regex("[\\p{L}\\p{N}._%+-]+@([\\p{L}\\p{N}-]+(?:\\.[\\p{L}\\p{N}-]+)+)")

    /** [text] with every e-mail address as `***@<domain>`. */
    fun masked(text: String): String = EMAIL.replace(text) { "***@" + it.groupValues[1] }
}
