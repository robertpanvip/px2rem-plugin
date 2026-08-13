package com.github.reactunitconverter.converter

import com.github.reactunitconverter.model.Px2RemConfig

/**
 * Converts pixel values (e.g. "16px", 16 as number) inside React inline style objects
 * into the target unit (rem or vw) respecting project px2rem config.
 *
 * Works with raw source snippets instead of PSI, which makes it usable both for the
 * inspection/action pipeline and for tests / headless CLI.
 */
class InlineStylePxConverter(private val cfg: Px2RemConfig) {

    data class Conversion(
        val range: IntRange,        // in the input text
        val original: String,       // "16px" or "16" (number literal)
        val converted: String,      // `${num}rem` / `${num}vw`
        val numericValue: Double,   // original px numeric
    )

    /**
     * Given a piece of source code that represents a React inline style object
     * (the inner of `style={{ ... }}`), scan it and return conversions to apply.
     * Only numeric literals that represent px (either quoted "16px" or bare numbers
     * for React.CSSProperties accepting pixels implicitly) are reported.
     *
     * @param styleBlock source of the style object, e.g. `{ marginTop: "16px", width: 100, zIndex: 2 }`
     * @param enclosingContext the JSX property name; expected "style" or a spread like style.
     */
    fun scan(styleBlock: String, enclosingContext: String = "style"): List<Conversion> {
        if (!enclosingContext.equals("style", ignoreCase = true) && !enclosingContext.endsWith("Style")) return emptyList()

        val result = mutableListOf<Conversion>()

        // Pass 1: find explicit `"Npx"` / `'Npx'` / ``Npx`` string values
        val pxStringRegex = Regex("""(['"`])\s*(-?\d+(?:\.\d+)?)\s*px\s*\1""", RegexOption.IGNORE_CASE)
        for (m in pxStringRegex.findAll(styleBlock)) {
            val numStr = m.groupValues[2]
            val num = numStr.toDoubleOrNull() ?: continue
            val prop = findPropNameBefore(styleBlock, m.range.first) ?: continue
            if (!cfg.isPropAllowed(cssPropOf(prop))) continue
            if (kotlin.math.abs(num) <= cfg.minPixelValue + 1e-9) continue
            val converted = formatNumber(convertPx(num)) + unitSuffix()
            result += Conversion(m.range, m.value, "\"$converted\"", num)
        }

        // Pass 2: bare numeric literals that follow a React.CSSProperties style key.
        // Matches patterns like `width: 100`, `marginTop: 16`
        val bareNumRegex = Regex("""([A-Za-z_][\w-]*)\s*:\s*(-?\d+(?:\.\d+)?)\s*[,}\]]""")
        for (m in bareNumRegex.findAll(styleBlock)) {
            val prop = m.groupValues[1]
            // Skip props that don't accept pixel units: zIndex, flex, fontWeight, opacity, order, etc.
            if (prop in NON_PIXEL_PROPS) continue
            if (prop.startsWith("zIndex")) continue
            if (prop == "fontWeight") continue
            if (prop == "opacity" || prop == "lineClamp") continue
            if (prop == "order" || prop == "flex" || prop == "flexGrow" || prop == "flexShrink") continue
            if (!cfg.isPropAllowed(cssPropOf(prop))) continue
            val numStr = m.groupValues[2]
            val num = numStr.toDoubleOrNull() ?: continue
            if (kotlin.math.abs(num) <= cfg.minPixelValue + 1e-9) continue
            // the match captured the last trailing char (,}]), don't include it
            val numRangeStart = m.range.first + m.groupValues[1].length + 1
            val colonIdx = styleBlock.indexOf(':', numRangeStart - m.groupValues[1].length - 1)
            val startOfNum = styleBlock.indexOf(numStr, colonIdx)
            val endOfNum = startOfNum + numStr.length - 1
            val original = numStr
            val converted = formatNumber(convertPx(num)) + unitSuffix()
            result += Conversion(startOfNum..endOfNum, original, "\"$converted\"", num)
        }

        // Pass 3: `calc()` expressions and template literals containing ${n}px patterns.
        val calcRegex = Regex("""calc\s*\(([^)]*)\)""", RegexOption.IGNORE_CASE)
        for (m in calcRegex.findAll(styleBlock)) {
            val inner = m.groupValues[1]
            val pxInCalc = Regex("""(-?\d+(?:\.\d+)?)\s*px""", RegexOption.IGNORE_CASE)
            for (sub in pxInCalc.findAll(inner)) {
                val num = sub.groupValues[1].toDoubleOrNull() ?: continue
                // try find prop for the overall calc() value
                val prop = findPropNameBefore(styleBlock, m.range.first) ?: continue
                if (!cfg.isPropAllowed(cssPropOf(prop))) continue
                if (kotlin.math.abs(num) <= cfg.minPixelValue + 1e-9) continue
                val absRange = (m.range.first + 1 + sub.range.first)..(m.range.first + 1 + sub.range.last)
                val converted = formatNumber(convertPx(num)) + unitSuffix()
                result += Conversion(absRange, sub.value, "$converted", num)
            }
        }

        return result
            .sortedByDescending { it.range.first }   // apply later changes first (text stays valid)
            .distinctBy { it.range }
    }

    /** Applies all conversions to [text] and returns the converted source. */
    fun apply(text: String, conversions: List<Conversion>): String {
        val sorted = conversions.sortedByDescending { it.range.first }
        var out = text
        for (c in sorted) {
            if (c.range.last >= out.length) continue
            out = out.substring(0, c.range.first) + c.converted + out.substring(c.range.last + 1)
        }
        return out
    }

    // --------------------------------------------------------------------------
    // Math & helpers
    // --------------------------------------------------------------------------

    private fun convertPx(px: Double): Double = when (cfg.unitToConvert) {
        "vw", "vh" -> {
            val base = if (cfg.unitToConvert == "vw") cfg.viewportWidth else cfg.viewportHeight
            if (base <= 0) px else px * 100.0 / base
        }
        "rem", "em" -> {
            if (cfg.rootValue <= 0) px else px / cfg.rootValue
        }
        else -> px
    }

    private fun unitSuffix(): String = when (cfg.unitToConvert) {
        "vw" -> "vw"
        "vh" -> "vh"
        "em" -> "em"
        else -> "rem"
    }

    private fun formatNumber(n: Double): String {
        val precision = cfg.unitPrecision.coerceIn(0, 12)
        val rounded = kotlin.math.round(n * kotlin.math.pow(10.0, precision.toDouble())) /
                kotlin.math.pow(10.0, precision.toDouble())
        // drop trailing zeros and potential '.'
        var s = rounded.toString()
        if (precision == 0) return s
        if ('e' in s || 'E' in s) return s
        if ('.' in s) {
            while (s.endsWith('0')) s = s.dropLast(1)
            if (s.endsWith('.')) s = s.dropLast(1)
        }
        return s
    }

    companion object {
        /** React CSS properties that expect a bare number and must NOT be treated as px. */
        private val NON_PIXEL_PROPS = setOf(
            "zIndex", "flex", "flexGrow", "flexShrink", "fontWeight", "opacity",
            "order", "lineClamp", "tabSize", "orphans", "widows", "columns", "columnCount",
            "animationIterationCount", "counterIncrement", "counterReset", "gridColumn", "gridRow"
        )

        /** Maps camelCase React.CSSProperties name to kebab-case CSS property name. */
        fun cssPropOf(reactProp: String): String = buildString {
            for (ch in reactProp) {
                if (ch.isUpperCase()) {
                    append('-')
                    append(ch.lowercase())
                } else append(ch)
            }
            if (startsWith("-web-kit-")) replace(0, 8, "-webkit-")
            if (startsWith("-moz-")) { /* ok */ }
        }

        /**
         * Finds the most recent property name before [idx] inside a JS/TS object literal.
         * Heuristic: look backwards for `key:` skipping strings/comments/etc.
         */
        fun findPropNameBefore(source: String, idx: Int): String? {
            var pos = idx.coerceAtMost(source.length - 1)
            var depth = 0
            while (pos >= 0) {
                val c = source[pos]
                when {
                    c == '"' || c == '\'' || c == '`' -> {
                        val s = skipStringBack(source, pos)
                        if (s == -1) return null
                        pos = s
                        continue
                    }
                    c == '}' || c == ']' -> depth++
                    c == '{' || c == '[' -> if (depth > 0) depth--
                    depth == 0 && c == ':' -> {
                        // walk backwards to find key
                        var end = pos - 1
                        while (end >= 0 && source[end].isWhitespace()) end--
                        if (end < 0) return null
                        if (source[end] == '"' || source[end] == '\'' || source[end] == '`') {
                            val quote = source[end]
                            var start = end - 1
                            while (start >= 0 && !(source[start] == quote && (start == 0 || source[start - 1] != '\\'))) start--
                            return source.substring(start + 1, end)
                        }
                        var start = end
                        while (start >= 0 && source[start].let { it.isLetterOrDigit() || it == '_' || it == '$' || it == '-' }) start--
                        return source.substring(start + 1, end + 1)
                    }
                }
                pos--
            }
            return null
        }

        private fun skipStringBack(s: String, endIdx: Int): Int {
            val quote = s[endIdx]
            var i = endIdx - 1
            while (i >= 0) {
                if (s[i] == '\\') { i -= 2; continue }
                if (s[i] == quote) return i
                i--
            }
            return -1
        }
    }
}
