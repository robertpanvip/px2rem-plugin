package com.github.reactunitconverter.converter

import com.github.reactunitconverter.model.Px2RemConfig
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DynamicInlineStyleConversionTest {

    @Test
    fun `variable references are wrapped with pxToRem`() {
        val cfg = Px2RemConfig.DEFAULT.copy(rootValue = 16.0)
        val c = InlineStylePxConverter(cfg)
        val style = """{ width: cardWidth, marginTop: spacing + 4, height: computeH() }"""
        val convs = c.scan(style)
        // width: cardWidth; marginTop: spacing + 4; height: computeH() → 3 dynamic values in total
        assertEquals(3, c.wrappedDynamicExpressions,
            "expected 3 dynamic wraps (cardWidth / spacing+4 / computeH()), got ${c.wrappedDynamicExpressions} with convs=$convs")
        assertTrue(convs.any { it.original.trim() == "cardWidth" && it.converted == "pxToRem(cardWidth)" })
        // binary spacing+4 wrapped too
        assertTrue(convs.any { it.isDynamic && it.original.contains("spacing + 4") && it.converted.startsWith("pxToRem(spacing + 4)") })
        // computeH() also wrapped
        assertTrue(convs.any { it.original.trim() == "computeH()" && it.converted == "pxToRem(computeH())" })
    }

    @Test
    fun `ternary and logical expressions are wrapped in rem helper`() {
        val cfg = Px2RemConfig.DEFAULT.copy(rootValue = 16.0)
        val c = InlineStylePxConverter(cfg)
        val style = """{ paddingLeft: big ? 40 : small ? 8 : 16, width: isFluid && fluidWidth || fallbackWidth }"""
        val convs = c.scan(style)
        // paddingLeft value is a ternary with literal numbers — but it's a value expression, scanner wraps whole
        assertTrue(convs.any { it.isDynamic && it.original.contains("big ?") && it.converted.startsWith("pxToRem(") })
        assertTrue(convs.any { it.isDynamic && it.original.contains("isFluid && fluidWidth") })
    }

    @Test
    fun `vw unit uses pxToVw helper with viewportWidth second arg`() {
        val cfg = Px2RemConfig.DEFAULT.copy(unitToConvert = "vw", viewportWidth = 750.0)
        val c = InlineStylePxConverter(cfg)
        val style = """{ width: layout.w, height: getH() }"""
        val out = c.apply(style, c.scan(style))
        assertTrue("pxToVw(layout.w, 750)" in out, "out=$out")
        assertTrue("pxToVw(getH(), 750)" in out, "out=$out")
    }

    @Test
    fun `already wrapped pxToRem calls are NOT re-wrapped`() {
        val cfg = Px2RemConfig.DEFAULT.copy(rootValue = 16.0)
        val c = InlineStylePxConverter(cfg)
        val style = """{ width: pxToRem(w), height: pxToVw(h, 750), zIndex: 2 }"""
        val before = c.wrappedDynamicExpressions
        val convs = c.scan(style)
        assertEquals(0, c.wrappedDynamicExpressions - before, "should not add new wraps, got=$convs")
        // static literals conversion should not happen for these values (they're dyn wrapped already)
    }

    @Test
    fun `spreads and nested objects arrays template strings are skipped by dynamic wrapper`() {
        val cfg = Px2RemConfig.DEFAULT.copy(rootValue = 16.0)
        val c = InlineStylePxConverter(cfg)
        val style = """{ ...base, transform: { scale: 2 }, foo: [1,2], bar: `hello` }"""
        val convs = c.scan(style)
        // No dynamic wraps should be emitted for these
        val dyns = convs.filter { it.isDynamic }
        assertTrue(dyns.isEmpty(), "dyns=$dyns")
    }

    @Test
    fun `static conversion still works alongside dynamic wraps`() {
        val cfg = Px2RemConfig.DEFAULT.copy(rootValue = 16.0)
        val c = InlineStylePxConverter(cfg)
        val style = """{ width: 32, height: dynH, marginTop: "48px", opacity: 0.5 }"""
        val result = c.apply(style, c.scan(style))
        assertTrue(result.contains("\"2rem\""), "width 32 -> 2rem expected: $result")
        assertTrue(result.contains("\"3rem\""), "marginTop 48px -> 3rem expected: $result")
        assertTrue(result.contains("pxToRem(dynH)"), "dynH wrapped: $result")
        assertTrue(result.contains("opacity: 0.5"), "opacity left alone: $result")
    }
}
