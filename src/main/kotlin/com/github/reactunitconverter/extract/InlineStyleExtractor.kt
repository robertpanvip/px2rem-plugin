package com.github.reactunitconverter.extract

/**
 * Extracts a React inline style object (`style={{ ... }}`) into a CSS Module class.
 *
 * Given the source of a style object, it:
 *   1. Converts React.CSSProperties camelCase keys -> kebab-case CSS properties
 *   2. Rewrites bare numeric values to `Npx` strings (React accepts numbers as px for most properties)
 *      EXCEPT for props like zIndex, flex, fontWeight, opacity, order, flexGrow...
 *   3. Formats a valid CSS rule body (`.classname { ... }`)
 *   4. Suggests a JSX replacement, e.g. `style={styles.className}` (plus any leftover spreads etc.)
 */
object InlineStyleExtractor {

    data class ExtractedCss(
        val className: String,
        val cssRuleBody: String,         // just the declarations block, without selector/braces
        val jsxReplacement: String,      // what to write back in place of the original style={{...}}
        val diagnostics: List<String> = emptyList(),
    )

    data class InlineStyleInfo(
        val styleObjectText: String,  // the inner object text, without the double braces
        val hasSpread: Boolean,       // e.g. style={{ ...common, color: "red" }}  -> spread means "style" stays (merged)
        val hasConditional: Boolean,  // e.g. style={active && {...}} or style={isBig ? {...} : {...}}
        val jsxBefore: String,        // text before style prop start (the JSX tag), for class name inference
        val jsxTag: String,           // e.g. "div", "button", "main", "MyComponent"
        val parentClassName: String?, // nearest className value (literal) found on this or parent JSX
        val siblingClassNames: List<String> = emptyList(),
    )

    /**
     * Build the extracted CSS.
     *
     * @param styleObjectText source of the React style object, e.g. `marginTop: "16px", width: 100`
     *                        (what lives inside `style={{ ... }}`). May include top-level `{ }`.
     * @param proposedClassName the proposed class name the user will (possibly) rename later
     * @param cssModuleImportName identifier used for module object, e.g. "styles" or "css"
     */
    fun extract(
        styleObjectText: String,
        proposedClassName: String,
        cssModuleImportName: String = "styles",
    ): ExtractedCss {
        val diags = mutableListOf<String>()
        val clean = stripOuterBraces(styleObjectText.trim())

        val spreadLines = mutableListOf<String>()
        val declarations = mutableListOf<String>()

        // Iterate object entries. Support bare spreads: `...obj`, `...foo(),`, and `key: value,`
        val entries = splitObjectEntries(clean)
        for (entry in entries) {
            val e = entry.trim().trimEnd(',').trim()
            if (e.isBlank()) continue
            if (e.startsWith("...")) {
                spreadLines += e
                continue
            }
            // key : value
            val kv = splitKv(e)
            if (kv == null) {
                diags += "Skip malformed style entry: $e"
                continue
            }
            val (rawKey, rawValue) = kv
            val key = unquote(rawKey.trim())
            val value = rawValue.trim()
            // skip known non-pixel numeric props: keep number as-is in CSS
            declarations += "${cssProp(key)}: ${valueToCss(key, value)};"
        }

        val hasSpread = spreadLines.isNotEmpty()
        val jsxReplacement = if (hasSpread) {
            // style={{ ...styles.foo, ...spreadA, ...spreadB }}
            val merged = buildString {
                append("style={{ ...$cssModuleImportName.")
                append(proposedClassName)
                for (s in spreadLines) append(", ").append(s)
                append(" }}")
            }
            merged
        } else {
            "style={$cssModuleImportName.$proposedClassName}"
        }

        val cssBody = declarations.joinToString("\n").trimIndent().let {
            if (it.isNotBlank()) it.prependIndent("  ") else ""
        }

        return ExtractedCss(
            className = proposedClassName,
            cssRuleBody = cssBody,
            jsxReplacement = jsxReplacement,
            diagnostics = diags,
        )
    }

    // -------- helpers ------

    private val NON_PIXEL_CSS_PROPS = setOf(
        "z-index", "flex", "flex-grow", "flex-shrink", "font-weight", "opacity",
        "order", "line-clamp", "tab-size", "orphans", "widows", "columns", "column-count",
        "animation-iteration-count", "grid-column", "grid-row", "counter-increment", "counter-reset"
    )

    private val INTEGER_PROPS = setOf(
        "z-index", "font-weight", "order", "columns", "column-count",
        "animation-iteration-count", "line-clamp", "orphans", "widows", "counter-increment", "counter-reset"
    )

    private fun cssProp(camelCase: String): String = buildString {
        for (ch in camelCase) {
            when {
                ch.isUpperCase() -> { append('-'); append(ch.lowercase()) }
                else -> append(ch)
            }
        }
        // prefix normalization
        var s = toString()
        if (s.startsWith("webkit-")) s = "-webkit-$s"
        else if (s.startsWith("moz-")) s = "-moz-$s"
        else if (s.startsWith("ms-")) s = "-ms-$s"
        else if (s.startsWith("o-")) s = "-o-$s"
        s
    }

    /** Converts a JS value (from the React style object) into a valid CSS declaration value. */
    private fun valueToCss(propCamelCase: String, rawValue: String): String {
        val cssProp = cssProp(propCamelCase)
        val v = rawValue.trim()

        // quoted string: take inner content
        if ((v.startsWith('"') && v.endsWith('"')) || (v.startsWith('\'') && v.endsWith('\''))) {
            return v.substring(1, v.length - 1).ifBlank { "0" }
        }
        if (v.startsWith('`') && v.endsWith('`')) return v.substring(1, v.length - 1).ifBlank { "0" }

        // plain numeric literal
        val num = v.toDoubleOrNull()
        if (num != null) {
            val isIntegral = num == num.toInt().toDouble()
            if (cssProp in NON_PIXEL_CSS_PROPS) {
                return if (cssProp in INTEGER_PROPS || isIntegral) num.toInt().toString() else num.toString()
            }
            // React treats numeric N as Npx for layout properties
            val asInt = if (isIntegral) num.toInt().toString() else num.toString()
            return "${asInt}px"
        }

        // template expression like `${x}px` or `calc(...)`
        // If it's a JS expression, fall back to using `var()` wrapping with a comment; we leave it as a CSS
        // custom property placeholder. Better: emit comment and keep original as-is.
        return "/* JS expression: $v; consider replacing with a CSS variable or static value */ 0px"
    }

    /** Strip leading `{` and trailing `}` if present (one level). */
    private fun stripOuterBraces(s: String): String {
        val t = s.trim()
        if (t.startsWith("{") && t.endsWith("}")) {
            var depth = 0
            var open = 0
            for (i in t.indices) {
                val c = t[i]
                if (c == '"' || c == '\'' || c == '`') {
                    val end = skipStr(t, i)
                    if (end != -1) continue
                }
                if (c == '{') { depth++; if (depth == 1) open = i }
                if (c == '}') { depth--; if (depth == 0 && i == t.length - 1) return t.substring(open + 1, i) }
            }
        }
        return t
    }

    private fun skipStr(s: String, start: Int): Int {
        val q = s[start]
        var i = start + 1
        while (i < s.length) {
            if (s[i] == '\\') { i += 2; continue }
            if (s[i] == q) return i
            i++
        }
        return -1
    }

    /** Split the body of an object literal (no outer braces) into top-level entries. */
    private fun splitObjectEntries(body: String): List<String> {
        val result = mutableListOf<String>()
        var depth = 0
        var arrDepth = 0
        var start = 0
        var i = 0
        while (i < body.length) {
            val c = body[i]
            if (c == '"' || c == '\'' || c == '`') {
                val end = skipStr(body, i)
                if (end != -1) { i = end + 1; continue }
            }
            when (c) {
                '{' -> depth++
                '}' -> depth--
                '[' -> arrDepth++
                ']' -> arrDepth--
                ',' -> if (depth == 0 && arrDepth == 0) {
                    result += body.substring(start, i)
                    start = i + 1
                }
            }
            i++
        }
        if (start < body.length) result += body.substring(start)
        return result
    }

    private fun unquote(s: String): String {
        val t = s.trim()
        return when {
            t.length >= 2 && ((t.startsWith('"') && t.endsWith('"')) || (t.startsWith('\'') && t.endsWith('\'')))
                -> t.substring(1, t.length - 1)
            else -> t
        }
    }

    private fun splitKv(entry: String): Pair<String, String>? {
        // Find the first ':' at depth 0 that is not inside a string.
        var depth = 0
        var arrDepth = 0
        var i = 0
        while (i < entry.length) {
            val c = entry[i]
            if (c == '"' || c == '\'' || c == '`') {
                val end = skipStr(entry, i)
                if (end != -1) { i = end + 1; continue }
            }
            when (c) {
                '{' -> depth++
                '}' -> depth--
                '[' -> arrDepth++
                ']' -> arrDepth--
                ':' -> if (depth == 0 && arrDepth == 0) {
                    val key = entry.substring(0, i)
                    val value = entry.substring(i + 1)
                    return key to value
                }
            }
            i++
        }
        return null
    }
}
