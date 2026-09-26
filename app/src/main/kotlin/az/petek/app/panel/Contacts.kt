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

package az.petek.app.panel

/**
 * Keeps tester contact data out of the panel's views (the PanelBackend contract): evidence texts such as oracle paths
 * and step details name testers by e-mail (`/test/tickets/latest?by=eli.a07@test.portal.example`); in a view only the
 * domain stays, so the owner still sees which system was asked.
 */
internal object Contacts {
    private val EMAIL = Regex("[\\p{L}\\p{N}._%+-]+@([\\p{L}\\p{N}-]+(?:\\.[\\p{L}\\p{N}-]+)+)")

    /** [text] with every e-mail address as `***@<domain>`. */
    fun masked(text: String): String = EMAIL.replace(text) { "***@" + it.groupValues[1] }
}
