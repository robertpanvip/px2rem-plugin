package com.github.reactunitconverter.extract

import com.github.reactunitconverter.converter.InlineStylePxConverter
import com.github.reactunitconverter.model.Px2RemConfig
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Headless tests for [StyleObjectExtraction.objectLiteralSource] — the pure text guard the
 * action uses when `style={styles}` references a style variable. PSI resolution itself is
 * IntelliJ-SDK-bound and can't run headless, so we test the text-extraction part here, plus
 * an integration test proving the extracted object converts and maps back into the declaration.
 */
class StyleObjectExtractionTest {

    @Test
    fun `typed CSSProperties declaration extracts object source`() {
        val src = "const styles: React.CSSProperties = { width: 100 }"
        assertEquals("{ width: 100 }", StyleObjectExtraction.objectLiteralSource(src))
    }

    @Test
    fun `untyped object declaration extracts object source`() {
        val src = "const styles = { width: 100, marginTop: 16 }"
        assertEquals("{ width: 100, marginTop: 16 }", StyleObjectExtraction.objectLiteralSource(src))
    }

    @Test
    fun `as-asserted object declaration extracts object source`() {
        val src = "const styles = { width: 100 } as React.CSSProperties"
        assertEquals("{ width: 100 }", StyleObjectExtraction.objectLiteralSource(src))
    }

    @Test
    fun `declaration without object initializer returns null`() {
        assertNull(StyleObjectExtraction.objectLiteralSource("const size = 16"))
        assertNull(StyleObjectExtraction.objectLiteralSource("const styles: React.CSSProperties"))
        assertNull(StyleObjectExtraction.objectLiteralSource("const styles = computeStyles()"))
        assertNull(StyleObjectExtraction.objectLiteralSource("const styles: React.CSSProperties = computeStyles()"))
    }

    @Test
    fun `strings containing braces do not confuse extraction`() {
        val src = "const styles = { content: '{}', width: 100 }"
        assertEquals("{ content: '{}', width: 100 }", StyleObjectExtraction.objectLiteralSource(src))
    }

    @Test
    fun `equal signs inside the object do not confuse the assignment locator`() {
        val src = "const styles = { width: theme == null ? 100 : 20 }"
        assertEquals("{ width: theme == null ? 100 : 20 }", StyleObjectExtraction.objectLiteralSource(src))
    }

    // Integration: mirrors the action path — extract the object literal from the declaration,
    // convert it with the standard converter, and map the result back into the declaration text.
    @Test
    fun `converted object maps back into declaration`() {
        val decl = "const styles: React.CSSProperties = { width: 100 }"
        val obj = StyleObjectExtraction.objectLiteralSource(decl)!!
        val cfg = Px2RemConfig(rootValue = 16.0, unitPrecision = 5)
        val conv = InlineStylePxConverter(cfg)
        val applied = conv.apply(obj, conv.scan(obj, "style"))
        assertEquals("const styles: React.CSSProperties = { width: \"6.25rem\" }", decl.replaceFirst(obj, applied))
    }

    @Test
    fun `non-pixel props inside style variable stay untouched by conversion`() {
        val decl = "const styles = { width: 100, zIndex: 3, fontWeight: 700 }"
        val obj = StyleObjectExtraction.objectLiteralSource(decl)!!
        val cfg = Px2RemConfig(rootValue = 16.0, unitPrecision = 5)
        val conv = InlineStylePxConverter(cfg)
        val applied = conv.apply(obj, conv.scan(obj, "style"))
        assertEquals(
            "const styles = { width: \"6.25rem\", zIndex: 3, fontWeight: 700 }",
            decl.replaceFirst(obj, applied)
        )
    }
}
