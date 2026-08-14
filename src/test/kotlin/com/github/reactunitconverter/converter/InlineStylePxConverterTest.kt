package com.github.reactunitconverter.converter

import com.github.reactunitconverter.model.Px2RemConfig
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InlineStylePxConverterTest {

    @Test
    fun `converts explicit px strings to rem`() {
        val cfg = Px2RemConfig.DEFAULT.copy(rootValue = 16.0, unitPrecision = 4)
        val c = InlineStylePxConverter(cfg)
        val text = """{ marginTop: "16px", paddingLeft: "24px", zIndex: 2 }"""
        val convs = c.scan(text)
        assertTrue(convs.any { it.original.contains("16px") })
        assertTrue(convs.any { it.converted.contains("1rem") })
        assertTrue(convs.any { it.original.contains("24px") })
        assertTrue(convs.any { it.converted.contains("1.5rem") })
        assertFalse(convs.any { it.numericValue.toInt() == 2 }) // zIndex skipped
    }

    @Test
    fun `converts bare numeric React props to rem strings`() {
        val cfg = Px2RemConfig.DEFAULT.copy(rootValue = 10.0, unitPrecision = 2)
        val c = InlineStylePxConverter(cfg)
        val text = """{ width: 100, height: 25, opacity: 0.5, zIndex: 10 }"""
        val convs = c.scan(text)
        // width 100 -> 10rem ; height 25 -> 2.5rem; opacity & zIndex not px-interpreted
        assertTrue(convs.any { it.original == "100" && it.converted == "\"10rem\"" })
        assertTrue(convs.any { it.original == "25" && it.converted == "\"2.5rem\"" })
        assertFalse(convs.any { it.original == "0.5" })
        assertFalse(convs.any { it.original == "10" })
    }

    @Test
    fun `honors minPixelValue`() {
        val cfg = Px2RemConfig.DEFAULT.copy(rootValue = 16.0, minPixelValue = 2.0)
        val c = InlineStylePxConverter(cfg)
        val text = """{ borderWidth: "1px", padding: "3px", margin: "2px" }"""
        val convs = c.scan(text)
        assertFalse(convs.any { it.original.contains("1px") })
        assertTrue(convs.any { it.original.contains("3px") })
        assertTrue(convs.any { it.original.contains("2px") })
    }

    @Test
    fun `propList exclusions respected`() {
        val cfg = Px2RemConfig.DEFAULT.copy(
            propList = listOf("*", "!font-size", "!fontSize"),
            rootValue = 16.0
        )
        val c = InlineStylePxConverter(cfg)
        val text = """{ fontSize: "24px", width: "32px" }"""
        val convs = c.scan(text)
        assertFalse(convs.any { it.numericValue.toInt() == 24 })
        assertTrue(convs.any { it.numericValue.toInt() == 32 })
    }

    @Test
    fun `vw conversion uses viewportWidth`() {
        val cfg = Px2RemConfig.DEFAULT.copy(unitToConvert = "vw", viewportWidth = 750.0, unitPrecision = 4)
        val c = InlineStylePxConverter(cfg)
        val text = """{ width: "75px", height: "150px" }"""
        val convs = c.scan(text)
        val out = c.apply(text, convs)
        // 75 / 750 * 100 = 10vw ; 150/750*100 = 20vw
        assertTrue(out.contains("\"10vw\""))
        assertTrue(out.contains("\"20vw\""))
    }

    @Test
    fun `apply replacements preserves order`() {
        val cfg = Px2RemConfig.DEFAULT.copy(rootValue = 16.0, unitPrecision = 4)
        val c = InlineStylePxConverter(cfg)
        val text = """{ a: "16px", b: "32px", c: "8px" }"""
        val convs = c.scan(text)
        val result = c.apply(text, convs)
        assertTrue(result.contains("\"1rem\""))
        assertTrue(result.contains("\"2rem\""))
        assertTrue(result.contains("\"0.5rem\""))
    }
}
