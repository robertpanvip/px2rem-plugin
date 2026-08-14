package com.github.reactunitconverter.config

import com.github.reactunitconverter.model.Px2RemConfig
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Px2RemConfigDetectorTest {

    @TempDir
    lateinit var tmp: File

    @Test
    fun `detects postcss-pxtorem in postcss config JS`() {
        val f = File(tmp, "postcss.config.js").apply {
            writeText(
                """
                module.exports = {
                  plugins: {
                    'postcss-pxtorem': {
                      rootValue: 75,
                      unitPrecision: 3,
                      propList: ['*', '!font-size'],
                      minPixelValue: 2
                    }
                  }
                };
                """.trimIndent()
            )
        }
        val cfg: Px2RemConfig = Px2RemConfigDetector(tmp).detect()
        assertTrue(cfg.cssLevelPluginEnabled, "expected cssLevelPluginEnabled=true for postcss config")
        assertEquals(75.0, cfg.rootValue)
        assertEquals(3, cfg.unitPrecision)
        assertEquals(2.0, cfg.minPixelValue)
        assertFalse(cfg.isPropAllowed("font-size"))
        assertTrue(cfg.isPropAllowed("width"))
    }

    @Test
    fun `detects vite plugin px2rem call`() {
        val f = File(tmp, "vite.config.ts").apply {
            writeText(
                """
                import { defineConfig } from 'vite'
                import px2rem from 'vite-plugin-px2rem'
                export default defineConfig({
                  plugins: [
                    px2rem({ rootValue: 75, unitPrecision: 4, viewportWidth: 750 })
                  ]
                })
                """.trimIndent()
            )
        }
        val cfg = Px2RemConfigDetector(tmp).detect()
        assertEquals(75.0, cfg.rootValue)
        assertEquals(4, cfg.unitPrecision)
    }

    @Test
    fun `detects rsbuild plugin options`() {
        File(tmp, "rsbuild.config.ts").writeText(
            """
            import { defineConfig } from '@rsbuild/core'
            import { pluginPx2rem } from '@rsbuild/plugin-px2rem'
            export default defineConfig({
              plugins: [
                pluginPx2rem({ rootValue: 37.5, unitPrecision: 5 })
              ]
            })
            """.trimIndent()
        )
        val cfg = Px2RemConfigDetector(tmp).detect()
        assertTrue(cfg.source.contains("rsbuild", ignoreCase = true))
        assertEquals(37.5, cfg.rootValue)
    }

    @Test
    fun `reads package json deps and px2rem field`() {
        File(tmp, "package.json").writeText(
            """
            {
              "name": "demo",
              "devDependencies": { "postcss-pxtorem": "^6.0.0" },
              "px2rem": { "rootValue": 32, "unitPrecision": 2, "unitToConvert": "vw", "viewportWidth": 720 }
            }
            """.trimIndent()
        )
        val cfg = Px2RemConfigDetector(tmp).detect()
        assertTrue(cfg.cssLevelPluginEnabled)
        assertEquals("vw", cfg.unitToConvert)
        assertEquals(720.0, cfg.viewportWidth)
        assertEquals(32.0, cfg.rootValue)
        assertEquals(2, cfg.unitPrecision)
    }

    @Test
    fun `yields defaults with no config files`() {
        val cfg = Px2RemConfigDetector(tmp).detect()
        assertEquals(Px2RemConfig.DEFAULT.rootValue, cfg.rootValue)
        assertEquals(Px2RemConfig.DEFAULT.unitToConvert, cfg.unitToConvert)
        assertFalse(cfg.cssLevelPluginEnabled)
        assertEquals("default", cfg.source)
    }
}
