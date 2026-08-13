package com.github.reactunitconverter.extract

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InlineStyleExtractorTest {

    @Test
    fun `converts simple style object to CSS with px suffix`() {
        val out = InlineStyleExtractor.extract(
            styleObjectText = """marginTop: 16, paddingLeft: "24px", width: 100, opacity: 0.5, zIndex: 10""",
            proposedClassName = "cardHeader"
        )
        val css = out.cssRuleBody
        assertEquals("style={styles.cardHeader}", out.jsxReplacement)
        assertTrue(css.contains("margin-top: 16px;"), "expected marginTop rendered, got: $css")
        assertTrue(css.contains("padding-left: 24px;"), "expected paddingLeft 24px rendered, got: $css")
        assertTrue(css.contains("width: 100px;"), "expected width 100px rendered, got: $css")
        assertTrue(css.contains("opacity: 0.5;"), "expected opacity numeric, got: $css")
        assertTrue(css.contains("z-index: 10;"), "expected z-index kept integer, got: $css")
    }

    @Test
    fun `spread results in merged style object`() {
        val out = InlineStyleExtractor.extract(
            styleObjectText = """...common, width: 100, ...otherStuff""",
            proposedClassName = "box"
        )
        assertEquals(
            "style={{ ...styles.box, ...common, ...otherStuff }}",
            out.jsxReplacement
        )
    }

    @Test
    fun `camelCase keys become kebab-case with vendor prefixes`() {
        val out = InlineStyleExtractor.extract(
            styleObjectText = """WebkitTransform: "rotate(90deg)", MsFlexAlign: "center" """,
            proposedClassName = "rot"
        )
        val css = out.cssRuleBody
        assertTrue(css.contains("-webkit-transform: rotate(90deg);"), "got: $css")
        assertTrue(css.contains("-ms-flex-align: center;"), "got: $css")
    }
}
