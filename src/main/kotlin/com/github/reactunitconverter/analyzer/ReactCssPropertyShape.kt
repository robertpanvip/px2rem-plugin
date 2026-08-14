package com.github.reactunitconverter.analyzer

/**
 * String-level React.CSSProperties shape heuristics — IDE/PSI-free so unit tests can run
 * against pure logic without the IntelliJ Platform SDK.
 *
 * The full PSI tracker ([ReactCssPropertyTracker]) uses these same rules before descending
 * into type-resolution; this class is intentionally duplicated (package-level internal) so
 * JVM tests can compile against `kotlin-stdlib` only.
 */
object ReactCssPropertyShape {

    @JvmStatic
    fun looksLikeReactCssProperties(typeText: String): Boolean {
        val t = typeText.trim().removeSurrounding("\"")
        if (t.contains("[")) return false
        if (t.endsWith("CSSProperties")) return true
        if (t.endsWith("CSSProperties>") && t.contains("Partial")) return true
        if (t.startsWith("CSSProperties")) return true
        if (t.contains("import('react')") && t.contains("CSSProperties")) return true
        return false
    }

    @JvmStatic
    fun looksLikeCssPropertyValueType(typeText: String): Boolean {
        val t = typeText.trim()
        if (t.contains("CSSPropertyValue")) return true
        if (t.contains("CSSProperties[") || t.contains("CSSProperties[\"") ||
            t.contains("CSSProperties['")) {
            // still skip known-unitless / non-pixel props that were indexed
            val prop = t.substringAfterLast("[").removePrefix("\"").removePrefix("'")
                .substringBefore("]").substringBefore("\"").substringBefore("'")
                .let { it.dropLastWhile { c -> c == '"' || c == '\'' } }
            if (isNonPixelPropName(prop)) return false
            return true
        }
        return false
    }

    private fun isNonPixelPropName(cssOrReactName: String): Boolean {
        val n = cssOrReactName.replace("-", "").lowercase()
        return n in setOf(
            "zindex", "fontweight", "opacity", "flex", "flexgrow", "flexshrink",
            "order", "lineclamp", "fontfamily", "fontstyle", "fontvariant", "fontstretch",
            "display", "position", "float", "clear", "visibility", "overflow",
            "overflowx", "overflowy", "boxsizing", "cursor", "pointerevents",
            "userselect", "resiz", "resize", "textalign", "justifycontent",
            "alignitems", "alignself", "aligncontent", "flexdirection", "flexwrap",
            "textdecoration", "texttransform", "whitewhite", "whitespace", "wordbreak",
            "wordwrap", "transitiontimingfunction", "animationtimingfunction",
            "transitionproperty", "animationname", "animationdirection", "animationfillmode",
            "animationplaystate", "color", "backgroundcolor", "backgroundrepeat",
            "backgroundattachment", "backgroundclip", "backgroundorigin",
            "backgroundblendmode", "mixblendmode", "borderstyle", "borderleftstyle",
            "borderrightstyle", "bordertopstyle", "borderbottomstyle", "bordercollapse",
            "borderspacing", "emptycells", "tablelayout", "liststyletype",
            "liststyleposition", "liststyleimage", "verticalalign", "writingmode",
            "direction", "unicodebidi", "boxdecorationbreak", "appearance", "willchange",
            "transformstyle", "backfacevisibility", "perspective", "imagesrendering",
            "shaperendering", "colorrendering", "textrendering", "fontkerning",
            "content", "quotes", "counterreset", "counterincrement", "pagesize",
            "size", "pagebreakafter", "pagebreakbefore", "pagebreakinside",
            "breakafter", "breakbefore", "breakinside", "orphans", "widows",
        )
    }

    /** Mirrors the STYLE_KEY_HINTS scoring of the PSI tracker for a plain key-set. */
    @JvmStatic
    fun treatKeysAsStyleLike(keys: Iterable<String>): Boolean {
        var hint = 0
        val hintSet = setOf(
            "width", "height", "top", "left", "right", "bottom",
            "margin", "padding", "border", "gap", "inset",
            "fontsize", "textindent", "letterspacing", "lineheight",
            "backgroundposition", "backgroundsize", "backgroundpositionx", "backgroundpositiony",
            "borderradius", "borderwidth", "borderleft", "borderright", "bordertop", "borderbottom",
            "marginleft", "marginright", "margintop", "marginbottom",
            "paddingleft", "paddingright", "paddingtop", "paddingbottom",
            "flexbasis", "gridrowgap", "gridcolumngap", "columnwidth", "columnrulewidth",
            "minwidth", "maxwidth", "minheight", "maxheight",
            "boxshadow", "textshadow", "strokewidth", "outlinewidth", "outlineoffset",
            "transformorigin", "translate", "perspectiveorigin",
            "rowgap", "columngap", "gridtemplatecolumns", "gridtemplaterows",
        )
        for (k in keys) {
            val n = k.replace("-", "").lowercase()
            if (n in hintSet) hint++
            if (n.startsWith("margin") || n.startsWith("padding") ||
                n.startsWith("border") || n.startsWith("background") ||
                n == "fontsize") {
                hint++
            }
        }
        return hint >= 1
    }
}
