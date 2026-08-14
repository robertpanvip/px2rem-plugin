package com.github.reactunitconverter.model

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Px2RemConfigPropListTest {

    @Test
    fun `star matches everything`() {
        val cfg = Px2RemConfig.DEFAULT.copy(propList = listOf("*"))
        assertTrue(cfg.isPropAllowed("width"))
        assertTrue(cfg.isPropAllowed("margin-top"))
        assertTrue(cfg.isPropAllowed("z-index"))
    }

    @Test
    fun `negated property excludes it even when star present`() {
        val cfg = Px2RemConfig.DEFAULT.copy(propList = listOf("*", "!font-size"))
        assertTrue(cfg.isPropAllowed("width"))
        assertFalse(cfg.isPropAllowed("font-size"))
        // camelCase should also map correctly -> cssPropOf("fontSize") == "font-size"
        assertFalse(cfg.isPropAllowed("fontSize"))
    }

    @Test
    fun `wildcard prefix and suffix patterns`() {
        val cfg = Px2RemConfig.DEFAULT.copy(propList = listOf("margin*", "padding*"))
        assertTrue(cfg.isPropAllowed("margin"))
        assertTrue(cfg.isPropAllowed("margin-top"))
        assertTrue(cfg.isPropAllowed("marginTop"))  // cssPropOf -> margin-top 也匹配 margin*
        assertTrue(cfg.isPropAllowed("padding-left"))
        assertFalse(cfg.isPropAllowed("width"))
        assertFalse(cfg.isPropAllowed("border-radius"))
    }

    @Test
    fun `mixed include only list uses explicit matches`() {
        val cfg = Px2RemConfig.DEFAULT.copy(propList = listOf("width", "height", "margin-*"))
        assertTrue(cfg.isPropAllowed("width"))
        assertTrue(cfg.isPropAllowed("height"))
        assertTrue(cfg.isPropAllowed("margin-top"))
        assertFalse(cfg.isPropAllowed("padding"))
        assertFalse(cfg.isPropAllowed("font-size"))
    }

    @Test
    fun `negated wildcards`() {
        val cfg = Px2RemConfig.DEFAULT.copy(propList = listOf("*", "!border*", "!margin-*"))
        assertTrue(cfg.isPropAllowed("width"))
        assertFalse(cfg.isPropAllowed("border"))
        assertFalse(cfg.isPropAllowed("border-radius"))
        assertFalse(cfg.isPropAllowed("margin-top"))
        // margin 本身不带短横，按 !margin-* 仍应允许吗？pattern "margin-*" 只匹配 margin-XXX → 因此 margin 单字允许
        assertTrue(cfg.isPropAllowed("margin"))
    }

    @Test
    fun `case insensitive matching`() {
        val cfg = Px2RemConfig.DEFAULT.copy(propList = listOf("Width", "!FONT-SIZE"))
        assertTrue(cfg.isPropAllowed("width"))
        assertTrue(cfg.isPropAllowed("WIDTH"))
        assertFalse(cfg.isPropAllowed("font-size"))
        assertFalse(cfg.isPropAllowed("fontSize"))
    }

    @Test
    fun `empty propList is treated as all`() {
        val cfg = Px2RemConfig.DEFAULT.copy(propList = emptyList())
        assertTrue(cfg.isPropAllowed("width"))
        assertTrue(cfg.isPropAllowed("margin-top"))
    }
}
