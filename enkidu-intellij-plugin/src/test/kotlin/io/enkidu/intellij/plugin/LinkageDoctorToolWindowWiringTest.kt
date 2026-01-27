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
import javax.swing.AbstractButton
import javax.swing.JComponent
import javax.swing.JTree
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LinkageDoctorToolWindowWiringTest : BasePlatformTestCase() {
    fun `test toolwindow panel creates key UI components and wires actions`() {
        val panel = LinkageDoctorToolWindowPanel(project)
        val root = panel.component

        // Sanity: panel contains a failures tree.
        val tree = findFirst(root) { it is JTree } as JTree?
        assertNotNull(tree)

        // Sanity: toolbar buttons exist and are wired.
        val runButton = findFirst(root) { it is AbstractButton && (it as AbstractButton).text == "Run" } as AbstractButton?
        assertNotNull(runButton)
        assertTrue(runButton!!.actionListeners.isNotEmpty())

        val export = findFirst(root) { it is AbstractButton && (it as AbstractButton).text == "Export" } as AbstractButton?
        assertNotNull(export)
        assertTrue(export!!.actionListeners.isNotEmpty())
    }

    private fun findFirst(
        root: JComponent,
        predicate: (Any) -> Boolean,
    ): Any? {
        if (predicate(root)) return root
        val stack = ArrayDeque<java.awt.Component>()
        stack.add(root)
        while (stack.isNotEmpty()) {
            val c = stack.removeFirst()
            if (predicate(c)) return c
            if (c is java.awt.Container) {
                c.components.forEach { stack.add(it) }
            }
        }
        return null
    }
}
