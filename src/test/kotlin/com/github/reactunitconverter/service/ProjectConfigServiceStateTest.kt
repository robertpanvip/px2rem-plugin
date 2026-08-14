package com.github.reactunitconverter.service

import com.github.reactunitconverter.model.Px2RemConfig
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * IDE-free tests for propList parsing + config conversion via the headless [ProjectConfigState].
 */
class ProjectConfigServiceStateTest {

    @Test
    fun `toConfig parses comma-separated propList including spaces and blanks`() {
        val state = ProjectConfigState(
            propList = " * , !font-size, , !margin*,width",
            unitToConvert = "rem",
            rootValue = 37.5,
            unitPrecision = 3,
            minPixelValue = 1.0,
            viewportWidth = 375.0,
            viewportHeight = 667.0,
            cssLevelPluginEnabled = true,
            detectedSource = "vite"
        )
        val cfg: Px2RemConfig = state.toConfig()
        assertEquals(listOf("*", "!font-size", "!margin*", "width"), cfg.propList)
        assertEquals("rem", cfg.unitToConvert)
        assertEquals(37.5, cfg.rootValue)
        assertEquals(3, cfg.unitPrecision)
        assertEquals(1.0, cfg.minPixelValue)
        assertEquals(375.0, cfg.viewportWidth)
        assertEquals(667.0, cfg.viewportHeight)
        assertTrue(cfg.cssLevelPluginEnabled)
        assertEquals("vite", cfg.source)
    }

    @Test
    fun `empty propList falls back to star`() {
        val cfg = ProjectConfigState(propList = "  , ,   ").toConfig()
        assertEquals(listOf("*"), cfg.propList)
        assertTrue(cfg.isPropAllowed("width"))
    }

    @Test
    fun `override values propagate correctly`() {
        val cfg = ProjectConfigState(
            overridden = true,
            detectedSource = "override",
            cssLevelPluginEnabled = false,
            unitToConvert = "vw",
            unitPrecision = 2,
            propList = "*",
            minPixelValue = 0.5,
            viewportWidth = 720.0,
            viewportHeight = 1280.0,
        ).toConfig()
        assertEquals("override", cfg.source)
        assertFalse(cfg.cssLevelPluginEnabled)
        assertEquals("vw", cfg.unitToConvert)
        assertEquals(2, cfg.unitPrecision)
        assertEquals(0.5, cfg.minPixelValue)
        assertEquals(720.0, cfg.viewportWidth)
        assertEquals(1280.0, cfg.viewportHeight)
    }

    // Bug #6: a project with NO auto-detection ("default") and NO override must fall back
    // to the app-level global defaults instead of the hardcoded project ones.
    @Test
    fun `no detection uses app level global defaults`() {
        val cfg = ProjectConfigState(
            detectedSource = "default",
            overridden = false,
            unitToConvert = "rem",
            rootValue = 16.0,
            unitPrecision = 5,
            minPixelValue = 0.0,
            viewportWidth = 750.0,
            // app-level defaults (from Settings → React Unit Converter)
            appDefaultUnitToConvert = "vw",
            appDefaultRootValue = 75.0,
            appDefaultUnitPrecision = 2,
            appDefaultViewportWidth = 375.0,
            appDefaultMinPixelValue = 2.0,
        ).toConfig()
        assertEquals("vw", cfg.unitToConvert, "app default unit must win when nothing detected")
        assertEquals(75.0, cfg.rootValue, "app default rootValue must win")
        assertEquals(2, cfg.unitPrecision, "app default precision must win")
        assertEquals(375.0, cfg.viewportWidth, "app default viewportWidth must win")
        assertEquals(2.0, cfg.minPixelValue, "app default minPixelValue must win")
    }

    @Test
    fun `detected config overrides app defaults`() {
        val cfg = ProjectConfigState(
            detectedSource = "vite.config.ts",
            overridden = false,
            unitToConvert = "rem",
            rootValue = 37.5,
            unitPrecision = 3,
            minPixelValue = 1.0,
            viewportWidth = 750.0,
            // app defaults set to something else — must NOT win over a real detection
            appDefaultUnitToConvert = "vw",
            appDefaultRootValue = 75.0,
            appDefaultUnitPrecision = 2,
            appDefaultViewportWidth = 375.0,
            appDefaultMinPixelValue = 2.0,
        ).toConfig()
        assertEquals("rem", cfg.unitToConvert)
        assertEquals(37.5, cfg.rootValue)
        assertEquals(3, cfg.unitPrecision)
        assertEquals(1.0, cfg.minPixelValue)
        assertEquals(750.0, cfg.viewportWidth)
    }

    @Test
    fun `user override wins over app defaults`() {
        val cfg = ProjectConfigState(
            overridden = true,
            detectedSource = "override",
            unitToConvert = "em",
            rootValue = 10.0,
            unitPrecision = 4,
            minPixelValue = 0.0,
            viewportWidth = 640.0,
            appDefaultUnitToConvert = "vw",
            appDefaultRootValue = 75.0,
            appDefaultUnitPrecision = 2,
            appDefaultViewportWidth = 375.0,
            appDefaultMinPixelValue = 2.0,
        ).toConfig()
        assertEquals("em", cfg.unitToConvert)
        assertEquals(10.0, cfg.rootValue)
        assertEquals(4, cfg.unitPrecision)
        assertEquals(0.0, cfg.minPixelValue)
        assertEquals(640.0, cfg.viewportWidth)
    }
}
