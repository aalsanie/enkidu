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
package io.enkidu.intellij.plugin

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.w3c.dom.Element
import java.io.InputStream
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LinkageDoctorToolWindowExtensionRegistrationTest : BasePlatformTestCase() {
    fun `test plugin xml registers tool window`() {
        val xml = loadPluginXml()
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml)
        doc.documentElement.normalize()

        val toolWindows = doc.getElementsByTagName("toolWindow")
        assertTrue(
            toolWindows.length > 0,
            "plugin.xml contains no <toolWindow> entries. Check META-INF/plugin.xml packaging.",
        )

        val matches =
            (0 until toolWindows.length)
                .map { toolWindows.item(it) }
                .filterIsInstance<Element>()
                .filter { it.getAttribute("id") == "Linkage Doctor" }

        assertTrue(
            matches.isNotEmpty(),
            "plugin.xml does not register toolWindow id='Linkage Doctor'. " +
                "Check META-INF/plugin.xml for the toolWindow entry and id spelling.",
        )

        matches.forEach { el ->
            val factory = el.getAttribute("factoryClass")
            kotlin.test.assertEquals(
                expected = "io.enkidu.intellij.plugin.LinkageDoctorToolWindowFactory",
                actual = factory,
                message = "toolWindow id='Linkage Doctor' factoryClass mismatch in plugin.xml",
            )
        }
    }

    private fun loadPluginXml(): InputStream {
        val stream =
            this::class.java.classLoader.getResourceAsStream("META-INF/plugin.xml")
                ?: this::class.java.classLoader.getResourceAsStream("/META-INF/plugin.xml")

        assertNotNull(
            stream,
            "Could not load META-INF/plugin.xml from test classpath. " +
                "Ensure enkidu-intellij-plugin resources are included in test runtime.",
        )
        return stream
    }
}
