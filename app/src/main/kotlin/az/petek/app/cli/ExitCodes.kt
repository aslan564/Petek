/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
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
