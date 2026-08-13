package com.github.reactunitconverter.converter

import com.github.reactunitconverter.model.Px2RemConfig

/**
 * Converts pixel values (e.g. "16px", 16 as number) inside React inline style objects
 * into the target unit (rem or vw) respecting project px2rem config.
 *
 * Static / literal values are converted in place: `"16px"` -> `"1rem"`, bare `100` -> `"6.25rem"`.
 * Dynamic expressions referenced by style property values (`someVar`, `getSize()`, `a ? b : c`,
 * `cond && obj`, optional chain `config?.width`, member access `theme.width`, binary expressions
 * that evaluate to a px number at runtime) are **wrapped** into a runtime helper call so the
 * conversion still happens at runtime using the project's default rootValue/viewportWidth.
 * The wrapping function defaults to `pxToRem`/`pxToVw` as generated in `src/utils/rem.ts` by
 * [PxToRemHelperService][com.github.reactunitconverter.runtime.PxToRemHelperService].
 *
 * Works with raw source snippets instead of PSI, which makes it usable both for the
 * inspection/action pipeline and for tests / headless CLI.
 */
class InlineStylePxConverter(
    private val cfg: Px2RemConfig,
    private val helperOpts: DynamicHelperOptions = DynamicHelperOptions(),
) {

    /**
     * @param helperFnName   runtime helper used for **dynamic** value wrapping: `pxToRem` / `pxToVw`.
     *                       Defaults based on `cfg.unitToConvert`.
     * @param emitImport     whether the caller (Action) is responsible for ensuring the helper import
     *                       is added; this converter only emits the call-site in the text replacement.
     * @param viewportWidthPassed when true and unit is vw, calls are emitted as `pxToVw(expr, viewportWidth)`
     *                            with the numeric viewportWidth inlined as 2nd arg.
     */
    data class DynamicHelperOptions(
        val helperFnName: String? = null,
        val emitImport: Boolean = true,
        val viewportWidthPassed: Boolean = true,
    )

    /** Whether at least one dynamic-expression wrap was emitted during [scan]. */
    var wrappedDynamicExpressions: Int = 0; private set
    /** Whether at least one static conversion happened. */
    var convertedStaticLiterals: Int = 0; private set

    private val effectiveHelperName: String = helperOpts.helperFnName
        ?: when (cfg.unitToConvert) {
            "vw" -> "pxToVw"
            "vh" -> "pxToVw"  // shared helper, user can rename
            else -> "pxToRem"
        }

    data class Conversion(
        val range: IntRange,        // in the input text
        val original: String,       // "16px" or "16" (number literal) or the whole dynamic expr
        val converted: String,      // `${num}rem` / `${num}vw` or `pxToRem(expr)`
        val numericValue: Double,   // original px numeric (Double.NaN if dynamic expr)
        val isDynamic: Boolean = false,
    )

    /**
     * Given a piece of source code that represents a React inline style object
     * (the inner of `style={{ ... }}`), scan it and return conversions to apply.
     *
     * @param styleBlock source of the style object, e.g. `{ marginTop: "16px", width: w, height: getH()+2 }`
     * @param enclosingContext the JSX property name; expected "style" or a spread like style.
     */
    fun scan(styleBlock: String, enclosingContext: String = "style"): List<Conversion> {
        if (!enclosingContext.equals("style", ignoreCase = true) && !enclosingContext.endsWith("Style")) return emptyList()
        wrappedDynamicExpressions = 0
        convertedStaticLiterals = 0

        val result = mutableListOf<Conversion>()

        // Pass 1: find explicit `"Npx"` / `'Npx'` / ``Npx`` string values
        val pxStringRegex = Regex("((?:'|\"|`))\\s*(-?\\d+(?:\\.\\d+)?)\\s*px\\s*\\1", RegexOption.IGNORE_CASE)
        for (m in pxStringRegex.findAll(styleBlock)) {
            val numStr = m.groupValues[2]
            val num = numStr.toDoubleOrNull() ?: continue
            val prop = findPropNameBefore(styleBlock, m.range.last) ?: continue
            if (!cfg.isPropAllowed(cssPropOf(prop))) continue
            if (kotlin.math.abs(num) + 1e-9 < cfg.minPixelValue) continue
            val converted = formatNumber(convertPx(num)) + unitSuffix()
            result += Conversion(m.range, m.value, "\"$converted\"", num)
            convertedStaticLiterals++
        }

        // Pass 2: bare numeric literals that follow a React.CSSProperties style key.
        // Matches patterns like `width: 100`, `marginTop: 16`
        val bareNumRegex = Regex("""([A-Za-z_][\w-]*)\s*:\s*(-?\d+(?:\.\d+)?)\s*[,}\]]""")
        for (m in bareNumRegex.findAll(styleBlock)) {
            val prop = m.groupValues[1]
            // Skip props that don't accept pixel units: zIndex, flex, fontWeight, opacity, order, etc.
            if (isNonPixelProp(prop)) continue
            if (!cfg.isPropAllowed(cssPropOf(prop))) continue
            val numStr = m.groupValues[2]
            val num = numStr.toDoubleOrNull() ?: continue
            if (kotlin.math.abs(num) + 1e-9 < cfg.minPixelValue) continue
            // the match captured the last trailing char (,}]), don't include it
            val colonIdx = styleBlock.indexOf(':', m.range.first)
            val startOfNum = colonIdx + 1
            val startTrim = styleBlock.indexOfFirstWhile(startOfNum, styleBlock.length) { !it.isWhitespace() }
            val actualStart = styleBlock.indexOf(numStr, startTrim)
            if (actualStart < 0) continue
            val endOfNum = actualStart + numStr.length - 1
            val converted = formatNumber(convertPx(num)) + unitSuffix()
            result += Conversion(actualStart..endOfNum, numStr, "\"$converted\"", num)
            convertedStaticLiterals++
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
                if (kotlin.math.abs(num) + 1e-9 < cfg.minPixelValue) continue
                val absRange = (m.range.first + 1 + sub.range.first)..(m.range.first + 1 + sub.range.last)
                val converted = formatNumber(convertPx(num)) + unitSuffix()
                result += Conversion(absRange, sub.value, "$converted", num)
                convertedStaticLiterals++
            }
        }

        // Pass 4: Dynamic expressions that ARE the value of a pixel-style property.
        // For each style prop, extract the value expression. If the expression is NOT a
        // static px string / bare number (already handled above) and looks like it
        // evaluates to a pixel number (variable, func call, ternary, binary arithmetic,
        // &&/||, member access, optional chain), wrap it with pxToRem()/pxToVw().
        result += scanDynamicStyleValues(styleBlock)

        return result
            .sortedByDescending { it.range.first }   // apply later changes first (text stays valid)
            .distinctBy { it.range }
    }

    /**
     * Scan each `key: value` inside [styleBlock]. When the value of a pixel-layout style
     * property is a dynamic expression (not just a literal), emit a Conversion that wraps
     * the entire value expression in the runtime helper.
     */
    private fun scanDynamicStyleValues(styleBlock: String): List<Conversion> {
        val out = mutableListOf<Conversion>()
        // Find top-level entry boundaries (comma-separated at depth 0, skipping strings)
        val entryRanges = splitStyleEntryRanges(styleBlock)
        for (entryRange in entryRanges) {
            val parsed = parseEntry(styleBlock, entryRange) ?: continue
            val (prop, valueRange) = parsed
            if (isNonPixelProp(prop)) continue
            if (!cfg.isPropAllowed(cssPropOf(prop))) continue
            val valueSrc = styleBlock.substring(valueRange)
            // Skip values we already converted: pure quoted "Npx" string / pure number literal
            if (isStaticPxStringLiteral(valueSrc) || isBareNumericLiteral(valueSrc)) continue
            // Skip already wrapped helper calls (idempotent: don't wrap wrappers of either unit)
            val head = valueSrc.trimStart()
            if (head.startsWith("pxToRem(") || head.startsWith("pxToVw(") ||
                head.startsWith("pxToVh(") || head.startsWith("$effectiveHelperName(")) continue
            // Skip template strings / nested object / arrays (spreads captured in ...x form aren't value props)
            if (head.startsWith("`") || head.startsWith("{") || head.startsWith("[")) continue
            // For a logical-and `cond && <styleValue>` or `a || b` we only want to wrap the RHS / both sides
            // if the whole thing becomes the value. For simple safety we wrap the entire expression (user can edit).
            val wrapped = wrapWithHelper(valueSrc)
            out += Conversion(
                range = valueRange,
                original = valueSrc,
                converted = wrapped,
                numericValue = Double.NaN,
                isDynamic = true,
            )
            wrappedDynamicExpressions++
        }
        return out
    }

    private fun wrapWithHelper(expr: String): String {
        val trimmed = expr.trim()
        val passViewport = helperOpts.viewportWidthPassed &&
                (effectiveHelperName == "pxToVw" || cfg.unitToConvert == "vw")
        return buildString {
            append(effectiveHelperName)
            append('(')
            append(trimmed)
            if (passViewport) {
                append(", ")
                append(cfg.viewportWidth.let { if (it == it.toLong().toDouble()) it.toLong().toString() else it.toString() })
            }
            append(')')
        }
    }

    private fun isStaticPxStringLiteral(valueSrc: String): Boolean {
        val s = valueSrc.trim()
        if (s.length < 3) return false
        val q = s.first()
        if ((q == '"' || q == '\'' || q == '`') && s.last() == q) {
            val inner = s.substring(1, s.length - 1)
            return Regex("""^\s*-?\d+(?:\.\d+)?\s*px\s*${'$'}""", RegexOption.IGNORE_CASE).matches(inner)
        }
        return false
    }

    private fun isBareNumericLiteral(valueSrc: String): Boolean = valueSrc.trim().toDoubleOrNull() != null

    /** Return a list of entry (start..end) ranges (absolute indices in styleBlock). */
    private fun splitStyleEntryRanges(source: String): List<IntRange> {
        val len = source.length
        var start = 0
        // If source starts with '{' and ends with matching '}', drop the outer braces.
        val limit: Int
        if (len > 0 && source[0] == '{') {
            val m = matchBalancedBracesAny(source, 0)
            start = 1
            limit = if (m >= 0) m - 1 else len - 1
        } else {
            limit = len - 1
        }
        val out = mutableListOf<IntRange>()
        var objDepth = 0
        var arrDepth = 0
        var i = start
        var entryStart = start
        while (i <= limit) {
            val c = source[i]
            if (c == '"' || c == '\'' || c == '`') {
                val end = skipStrForward(source, i)
                if (end != -1) { i = end + 1; continue }
            }
            when (c) {
                '{' -> objDepth++
                '}' -> if (objDepth > 0) objDepth--
                '[' -> arrDepth++
                ']' -> if (arrDepth > 0) arrDepth--
                ',' -> if (objDepth == 0 && arrDepth == 0) {
                    out += entryStart..(i - 1)
                    entryStart = i + 1
                }
            }
            i++
        }
        if (entryStart <= limit) {
            // trim trailing whitespace / unmatched closing brace artifacts
            var e = limit
            while (e >= entryStart && source[e].let { it.isWhitespace() || it == '}' }) e--
            if (e >= entryStart) out += entryStart..e
        }
        return out
    }

    /** Parse one `key: value` entry. Returns (propName, value absolute range in styleBlock). */
    private fun parseEntry(source: String, entryRange: IntRange): Pair<String, IntRange>? {
        var i = entryRange.first
        while (i <= entryRange.last && source[i].isWhitespace()) i++
        if (i > entryRange.last) return null
        // Skip spread entries: "...foo" or "...foo()" -> not a style property we need to wrap.
        if (source[i] == '.' && i + 1 <= entryRange.last && source[i + 1] == '.') return null
        val key: String
        val afterKey: Int
        val first = source[i]
        when (first) {
            '"', '\'', '`' -> {
                val end = skipStrForward(source, i)
                if (end < 0) return null
                key = source.substring(i + 1, end)
                afterKey = end + 1
            }
            else -> {
                val ks = i
                while (i <= entryRange.last && source[i].let { it.isLetterOrDigit() || it == '_' || it == '$' || it == '-' }) i++
                if (ks == i) return null
                key = source.substring(ks, i)
                afterKey = i
            }
        }
        var col = afterKey
        while (col <= entryRange.last && source[col].isWhitespace()) col++
        if (col > entryRange.last || source[col] != ':') return null
        var vs = col + 1
        while (vs <= entryRange.last && source[vs].isWhitespace()) vs++
        if (vs > entryRange.last) return null
        var ve = entryRange.last
        while (ve >= vs && source[ve].isWhitespace()) ve--
        if (ve < vs) return null
        return key to (vs..ve)
    }

    private fun skipStrForward(s: String, start: Int): Int {
        val q = s[start]
        var i = start + 1
        while (i < s.length) {
            val c = s[i]
            if (c == '\\') { i += 2; continue }
            if (c == q) return i
            // Multi-line template literals are fine; don't break on newline.
            i++
        }
        return -1
    }

    private fun matchBalancedBracesAny(s: String, openIdx: Int): Int {
        if (openIdx < 0 || openIdx >= s.length) return -1
        if (s[openIdx] != '{') return -1
        var depth = 0
        var i = openIdx
        while (i < s.length) {
            when (val c = s[i]) {
                '"', '\'', '`' -> {
                    val end = skipStrForward(s, i)
                    i = if (end == -1) s.length else end + 1
                    continue
                }
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
            i++
        }
        return -1
    }

    private fun String.indexOfFirstWhile(from: Int, to: Int, pred: (Char) -> Boolean): Int {
        for (i in from until to) if (pred(this[i])) return i
        return to
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
        val factor = Math.pow(10.0, precision.toDouble())
        val rounded = Math.round(n * factor) / factor
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
            "animationIterationCount", "counterIncrement", "counterReset", "gridColumn", "gridRow",
            "columnSpan", "rowSpan"
        )

        /** Returns true when a React.CSSProperties key never represents a pixel length. */
        internal fun isNonPixelProp(prop: String): Boolean {
            if (prop in NON_PIXEL_PROPS) return true
            val lower = prop.lowercase()
            // prefix/suffix rules: keep these out of px conversion
            if (lower.startsWith("zindex") || lower.startsWith("z-index")) return true
            if (lower == "fontweight" || lower == "font-weight") return true
            if (lower == "opacity") return true
            if (lower == "order" || lower == "lineclamp" || lower == "line-clamp") return true
            if (lower == "flex" || lower == "flexgrow" || lower == "flexshrink" ||
                lower == "flex-grow" || lower == "flex-shrink") return true
            if (lower == "tab-size" || lower == "tabsize") return true
            return false
        }

        /** Maps camelCase React.CSSProperties name to kebab-case CSS property name. */
        fun cssPropOf(reactProp: String): String {
            val sb = StringBuilder(reactProp.length + 4)
            for (ch in reactProp) {
                when {
                    ch.isUpperCase() -> { sb.append('-'); sb.append(ch.lowercase()) }
                    else -> sb.append(ch)
                }
            }
            var s = sb.toString()
            if (s.startsWith("webkit-")) s = "-webkit-$s"
            else if (s.startsWith("moz-")) s = "-moz-$s"
            else if (s.startsWith("ms-")) s = "-ms-$s"
            else if (s.startsWith("o-")) s = "-o-$s"
            return s
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
                        pos = s - 1
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
