/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

plugins {
    id("petek.kotlin-jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
    `java-test-fixtures`
}

dependencies {
    api(project(":core:domain"))
    api(libs.kotlinx.serialization.json)
    implementation(libs.anthropic.java)
    implementation(libs.kotlin.logging)
    // The OpenAI-compatible adapter (one HTTP client for OpenAI, Ollama, Groq, Mistral, OpenRouter, LM Studio...).
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    testImplementation(testFixtures(project(":core:domain")))
    // Embedded HTTP server standing in for the Messages API and chat/completions in the HTTP client tests.
    testImplementation(libs.ktor.server.core)
    testImplementation(libs.ktor.server.cio)
}
