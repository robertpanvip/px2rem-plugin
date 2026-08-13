package com.github.reactunitconverter.extract

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ClassNameInferencerTest {

    @Test
    fun `uses existing className first`() {
        val ctx = ClassNameInferencer.Context(
            jsxTag = "div",
            currentClassName = "card-header-item",
            existingClassNames = emptySet()
        )
        val n = ClassNameInferencer.suggest(ctx)
        assertEquals("cardHeaderItem", n)
    }

    @Test
    fun `deduplicates against existing class names`() {
        val ctx = ClassNameInferencer.Context(
            jsxTag = "div",
            currentClassName = "foo",
            existingClassNames = setOf("foo", "foo2")
        )
        val n = ClassNameInferencer.suggest(ctx)
        assertEquals("foo3", n)
    }

    @Test
    fun `falls back to tag and style hints`() {
        val ctx = ClassNameInferencer.Context(
            jsxTag = "Button",
            styleProps = mapOf("backgroundColor" to "red", "borderRadius" to 8, "padding" to "12px")
        )
        val n = ClassNameInferencer.suggest(ctx)
        assertTrue(n.contains("button", ignoreCase = true) ||
                n.contains("rounded") || n.contains("bg") || n.contains("bordered"),
            "expected style/tag based name, got: $n")
        assertFalse(n.isBlank())
    }

    @Test
    fun `infers body sibling from header`() {
        val ctx = ClassNameInferencer.Context(
            jsxTag = "div",
            siblingClassNames = listOf("card-header", "card-foo-header")
        )
        val n = ClassNameInferencer.suggest(ctx)
        assertTrue(n.contains("body", ignoreCase = true), "expected body inference, got: $n")
    }

    @Test
    fun `parent plus tag composition`() {
        val ctx = ClassNameInferencer.Context(
            jsxTag = "input",
            parentClassName = "Form"
        )
        val n = ClassNameInferencer.suggest(ctx)
        assertTrue(n.contains("form", ignoreCase = true) && n.contains("input", ignoreCase = true),
            "expected FormInput-like composition, got: $n")
    }
}
