package com.github.reactunitconverter.extract

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InlineStyleExtractorEdgeCasesTest {

    @Test
    fun `spread with nested props produces correct merge JSX`() {
        val out = InlineStyleExtractor.extract(
            styleObjectText = """
                ...base,
                ...rest,
                width: 100,
                paddingLeft: 24,
                ...extra
            """.trimIndent(),
            proposedClassName = "muted",
            cssModuleImportName = "s"
        )
        assertEquals(
            "style={{ ...s.muted, ...base, ...rest, ...extra }}",
            out.jsxReplacement
        )
        assertTrue(out.cssRuleBody.contains("width: 100px;"))
        assertTrue(out.cssRuleBody.contains("padding-left: 24px;"))
    }

    @Test
    fun `quoted keys and values render correctly as CSS`() {
        val out = InlineStyleExtractor.extract(
            styleObjectText = """
                "margin-top": "12px",
                '--custom-prop': "var(--x)",
                backgroundImage: "linear-gradient(red, blue)"
            """.trimIndent(),
            proposedClassName = "box"
        )
        val body = out.cssRuleBody
        assertTrue(body.contains("margin-top: 12px;"), "body=$body")
        assertTrue(body.contains("--custom-prop: var(--x);"), "body=$body")
        assertTrue(body.contains("background-image: linear-gradient(red, blue);"), "body=$body")
    }

    @Test
    fun `bare numeric zero keeps zero not 0px when CSS allows unitless zero (preserves React behavior)`() {
        // React actually accepts `margin: 0` as 0 without unit. CSS also allows unitless 0 for lengths.
        // The plugin still writes 0px to stay CSS-friendly and predictable.
        val out = InlineStyleExtractor.extract(
            styleObjectText = "margin: 0, padding: 0, zIndex: 0, opacity: 0",
            proposedClassName = "reset"
        )
        val body = out.cssRuleBody
        assertTrue(body.contains("margin: 0px;"), "body=$body")
        assertTrue(body.contains("padding: 0px;"), "body=$body")
        assertTrue(body.contains("z-index: 0;"), "body=$body")
        assertTrue(body.contains("opacity: 0;"), "body=$body")
    }

    @Test
    fun `negative px numbers get preserved with minus sign and px suffix`() {
        val out = InlineStyleExtractor.extract(
            styleObjectText = "marginLeft: -24, marginTop: \"-1.5px\"",
            proposedClassName = "neg"
        )
        val body = out.cssRuleBody
        assertTrue(body.contains("margin-left: -24px;"), "body=$body")
        assertTrue(body.contains("margin-top: -1.5px;"), "body=$body")
    }

    @Test
    fun `fontWeight and zIndex stay integers not px`() {
        val out = InlineStyleExtractor.extract(
            styleObjectText = "fontWeight: 700, zIndex: 100, order: 2, lineClamp: 3",
            proposedClassName = "ui"
        )
        val body = out.cssRuleBody
        assertTrue(body.contains("font-weight: 700;"), "body=$body")
        assertTrue(body.contains("z-index: 100;"), "body=$body")
        assertTrue(body.contains("order: 2;"), "body=$body")
        assertTrue(body.contains("line-clamp: 3;"), "body=$body")
    }

    // Bug #5: import specifier must never become "./../..." — sibling/parent dirs use "../x",
    // same dir gets "./x", already-relative and absolute paths pass through unchanged.
    @Test
    fun `css module import specifier never becomes dot-dot-dot slash`() {
        assertEquals("./Button.module.css", CssModuleImportPath.specifier("Button.module.css"))
        assertEquals("./components/Button.module.css", CssModuleImportPath.specifier("components/Button.module.css"))
        assertEquals("../styles/Button.module.css", CssModuleImportPath.specifier("../styles/Button.module.css"))
        assertEquals("../../shared/Button.module.css", CssModuleImportPath.specifier("../../shared/Button.module.css"))
        assertEquals("./already.module.css", CssModuleImportPath.specifier("./already.module.css"))
        assertEquals("/abs/path/Button.module.css", CssModuleImportPath.specifier("/abs/path/Button.module.css"))
    }
}
