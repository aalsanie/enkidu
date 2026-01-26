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
package io.enkidu.artifacts.v1

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.core.util.DefaultIndenter
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter
import com.fasterxml.jackson.databind.MapperFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.ObjectWriter
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule

/**
 * JSON configuration for Enkidu artifacts.
 */
object EnkiduJson {
    /**
     * Pretty printer used for golden-file contracts and human-facing exports.
     *
     * Note: we intentionally do NOT rely on newer DefaultPrettyPrinter fluent APIs
     * (like withObjectFieldValueSeparator) to stay compatible across Jackson 2.x versions.
     */
    private val ENKIDU_PRETTY_PRINTER: DefaultPrettyPrinter = EnkiduPrettyPrinter()

    val mapper: ObjectMapper =
        JsonMapper
            .builder()
            .addModule(KotlinModule.Builder().build())
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(JsonGenerator.Feature.AUTO_CLOSE_TARGET)
            .serializationInclusion(JsonInclude.Include.NON_NULL)
            .build()

    /**
     * Pretty writer used for golden-file contracts and human-facing exports.
     */
    val prettyWriter: ObjectWriter = mapper.writer(ENKIDU_PRETTY_PRINTER)

    /**
     * DefaultPrettyPrinter subclass to guarantee ": " between field name and value
     * without depending on newer Jackson APIs.
     */
    private class EnkiduPrettyPrinter : DefaultPrettyPrinter() {
        init {
            indentObjectsWith(DefaultIndenter("  ", "\n"))
            indentArraysWith(DefaultIndenter("  ", "\n"))
        }

        override fun writeObjectFieldValueSeparator(g: JsonGenerator) {
            g.writeRaw(": ")
        }

        override fun createInstance(): DefaultPrettyPrinter = EnkiduPrettyPrinter()
    }
}
