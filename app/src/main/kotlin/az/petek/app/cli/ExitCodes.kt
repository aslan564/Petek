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

package az.petek.app.cli

/** Process exit codes of `petek`, stable for scripts and CI. */
object ExitCodes {
    /** Success; for `run`: the run PASSED. */
    const val OK = 0

    /** A check or run found problems (run FAILED, invalid campaign for `plan`, doctor/probe not green). */
    const val FAILURE = 1

    /** Nothing could be done: wrong command line, invalid configuration, refused target, or a run that was ABORTED. */
    const val CONFIG_OR_ABORTED = 2

    /** Interrupted (Ctrl+C); the run was still torn down and reported. */
    const val INTERRUPTED = 130
}
