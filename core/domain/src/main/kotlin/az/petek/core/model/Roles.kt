/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.core.model

/** Tester roles on the target system. Shared by campaign quotas, identity registry and actor selection. */
enum class Role(
    val key: String,
) {
    ADMIN("admin"),
    MANAGER("manager"),
    EMPLOYEE("employee"),
    ;

    companion object {
        fun fromKey(key: String): Role? = entries.firstOrNull { it.key == key.trim().lowercase() }
    }
}

/** How a tester gets into the company: the admin creates it; others join by invitation or by company code. */
enum class RegistrationMode(
    val key: String,
) {
    OWNER("owner"),
    INVITE("invite"),
    COMPANY_CODE("company_code"),
    ;

    companion object {
        fun fromKey(key: String): RegistrationMode? = entries.firstOrNull { it.key == key.trim().lowercase() }
    }
}
