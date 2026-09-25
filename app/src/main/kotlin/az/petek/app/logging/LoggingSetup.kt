/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.app.logging

import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.joran.JoranConfigurator
import ch.qos.logback.core.joran.spi.JoranException
import org.slf4j.LoggerFactory
import java.nio.file.Path

/** How the process logs; derived from the configuration and the command line. */
data class LoggingSettings(
    /** The rolling log file goes here (`<evidence>/logs/petek.log`). */
    val directory: Path,
    /** `--verbose`: DEBUG for Pətək and every console line. */
    val verbose: Boolean,
    /**
     * Whether stdout is a terminal showing the live board. The console then only gets warnings (the board shows
     * progress); in CI, progress lines go to the console as well.
     */
    val interactive: Boolean,
)

/**
 * Applies [LoggingSettings] to logback (`logback.xml` in the app's resources). The log directory is only known once
 * the configuration is loaded, so the settings are published as system properties and logback is reconfigured.
 * Properties are set before logback is first touched, so an automatic initialisation already uses them.
 */
object LoggingSetup {
    const val DIRECTORY_PROPERTY = "petek.log.dir"
    const val LEVEL_PROPERTY = "petek.log.level"
    const val CONSOLE_LEVEL_PROPERTY = "petek.console.level"
    private const val CONFIGURATION = "/logback.xml"

    fun apply(settings: LoggingSettings) {
        System.setProperty(DIRECTORY_PROPERTY, settings.directory.toAbsolutePath().toString())
        System.setProperty(LEVEL_PROPERTY, if (settings.verbose) "DEBUG" else "INFO")
        System.setProperty(CONSOLE_LEVEL_PROPERTY, consoleLevel(settings))
        val context = LoggerFactory.getILoggerFactory() as? LoggerContext ?: return
        val configuration = LoggingSetup::class.java.getResource(CONFIGURATION) ?: return
        context.reset()
        try {
            JoranConfigurator().apply { this.context = context }.doConfigure(configuration)
        } catch (e: JoranException) {
            System.err.println("Logging could not be configured: ${e.message}")
        }
    }

    fun consoleLevel(settings: LoggingSettings): String =
        when {
            settings.verbose -> "DEBUG"
            settings.interactive -> "WARN"
            else -> "INFO"
        }
}
