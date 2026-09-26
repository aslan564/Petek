/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.app

import java.util.Properties

/** The build's version (`version` in gradle.properties), written into the resources by the build; `dev` when absent. */
object PetekVersion {
    private const val RESOURCE = "version.properties"
    const val UNKNOWN = "dev"

    val current: String by lazy {
        PetekVersion::class.java.getResourceAsStream(RESOURCE)?.use { stream ->
            Properties().apply { load(stream) }.getProperty("version")?.takeIf { it.isNotBlank() && !it.contains("\${") }
        } ?: UNKNOWN
    }
}
