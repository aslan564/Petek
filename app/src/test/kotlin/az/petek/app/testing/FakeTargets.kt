/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.app.testing

import az.petek.faketarget.FakeTargetServer
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/** Helpers that drive the fake target the way a browser would, for tests that need data on it. */
object FakeTargets {
    /** Signs an owner up through the `/register` form and returns the id of the company the target created. */
    fun registerOwner(
        server: FakeTargetServer,
        email: String,
        company: String = "Pətək Test MMC",
    ): String {
        val form =
            mapOf(
                "name" to "Test Owner",
                "email" to email,
                "phone" to "+994501234567",
                "password" to "Correct-Horse-9",
                "company" to company,
            ).entries.joinToString("&") { (key, value) -> "$key=${URLEncoder.encode(value, Charsets.UTF_8)}" }
        HttpClient.newHttpClient().use { client ->
            val response =
                client.send(
                    HttpRequest
                        .newBuilder(URI("${server.baseUrl}/register"))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(form))
                        .build(),
                    HttpResponse.BodyHandlers.discarding(),
                )
            check(response.statusCode() in 200..399) { "sign-up failed with HTTP ${response.statusCode()}" }
        }
        return server.store.companies
            .single { it.ownerEmail == email }
            .id
    }
}
