/*
 * Copyright © 2025-2026 | Enkidu linkage doctor catches runtime linkage failures before runtime
 *
 * Author: @aalsanie
 *
 * Plugin: https://plugins.jetbrains.com/plugin/29504-shamash
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.enkidu.cli

import picocli.CommandLine
import kotlin.system.exitProcess

/**
 * Enkidu CLI entrypoint.
 */
object EnkiduCli {
    @JvmStatic
    fun main(args: Array<String>) {
        val exitCode = commandLine().execute(*args)
        exitProcess(exitCode)
    }

    internal fun commandLine(): CommandLine =
        CommandLine(RootCommand())
            .setCaseInsensitiveEnumValuesAllowed(true)
}

@CommandLine.Command(
    name = "enkidu",
    mixinStandardHelpOptions = true,
    subcommands = [DoctorCommand::class, CompareCommand::class],
    description = ["Enkidu Linkage Doctor — predict runtime linkage failures against an explicit runtime classpath."],
)
internal class RootCommand : Runnable {
    override fun run() {
        // picocli will show usage via -h/--help. Default command does nothing.
    }
}
