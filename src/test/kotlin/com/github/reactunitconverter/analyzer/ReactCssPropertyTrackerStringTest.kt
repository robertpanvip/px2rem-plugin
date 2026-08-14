package com.github.reactunitconverter.analyzer

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * IDE/PSI-free tests for [ReactCssPropertyShape] — the string-level heuristics used by
 * [ReactCssPropertyTracker] to decide whether an expression represents a `CSSProperties`
 * object, a single CSS-property value type, or a style-like object literal.
 */
class ReactCssPropertyTrackerStringTest {

    // --- looksLikeReactCssProperties ---
    @Test
    fun `recognizes CSSProperties type annotations in various forms`() {
        assertTrue(ReactCssPropertyShape.looksLikeReactCssProperties("React.CSSProperties"))
        assertTrue(ReactCssPropertyShape.looksLikeReactCssProperties("CSSProperties"))
        assertTrue(ReactCssPropertyShape.looksLikeReactCssProperties("Partial<CSSProperties>"))
        assertTrue(ReactCssPropertyShape.looksLikeReactCssProperties("import('react').CSSProperties"))
        assertFalse(ReactCssPropertyShape.looksLikeReactCssProperties("CSSProperties[\"width\"]"))
    }

    // --- looksLikeCssPropertyValueType ---
    @Test
    fun `recognizes indexed CSSProperties value types`() {
        assertTrue(ReactCssPropertyShape.looksLikeCssPropertyValueType("React.CSSProperties[\"width\"]"))
        assertTrue(ReactCssPropertyShape.looksLikeCssPropertyValueType("CSSProperties['margin-top']"))
        assertFalse(ReactCssPropertyShape.looksLikeCssPropertyValueType("CSSProperties[\"zIndex\"]"))
        assertFalse(ReactCssPropertyShape.looksLikeCssPropertyValueType("CSSProperties[\"opacity\"]"))
    }

    @Test
    fun `recognizes CSSPropertyValue forms`() {
        assertTrue(ReactCssPropertyShape.looksLikeCssPropertyValueType("React.CSSPropertyValue"))
        assertTrue(ReactCssPropertyShape.looksLikeCssPropertyValueType("CSSPropertyValue<number>"))
    }

    // --- treatKeysAsStyleLike ---
    @Test
    fun `style object heuristic detects style-like objects by property names`() {
        assertTrue(ReactCssPropertyShape.treatKeysAsStyleLike(listOf("width", "height", "padding")))
        assertTrue(ReactCssPropertyShape.treatKeysAsStyleLike(listOf("marginTop", "borderRadius", "backgroundColor")))
        assertFalse(ReactCssPropertyShape.treatKeysAsStyleLike(listOf("onClick", "href", "title")))
        assertFalse(ReactCssPropertyShape.treatKeysAsStyleLike(listOf("foo", "bar")))
        assertTrue(ReactCssPropertyShape.treatKeysAsStyleLike(listOf("label", "width")))
    }
}
