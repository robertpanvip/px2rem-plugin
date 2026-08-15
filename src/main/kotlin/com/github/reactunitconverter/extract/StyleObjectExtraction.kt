package com.github.reactunitconverter.extract

/**
 * Pure text helpers for recognizing a "style variable" declaration — IDE/PSI-free so unit
 * tests can run headless. Used by
 * [com.github.reactunitconverter.action.ConvertInlineStyleAction] as a guard before treating
 * a resolved `style={styles}` reference as convertible, so the string-level logic stays the
 * single tested source of truth (same pattern as the tracker / shape split).
 */
object StyleObjectExtraction {

    /**
     * Extract the object-literal initializer source from a style variable declaration text.
     *
     * Supported shapes:
     *   `const styles: React.CSSProperties = { width: 100 }`
     *   `const styles = { width: 100, marginTop: 16 }`
     *   `const styles = { width: 100 } as React.CSSProperties`
     *
     * Returns null when there is no object-literal initializer, e.g.
     *   `const size = 16`, `const styles: React.CSSProperties`,
     *   `const styles = computeStyles()`, `const styles: React.CSSProperties = computeStyles()`.
     *
     * The check is deliberately conservative (text-level): the caller additionally requires
     * the PSI initializer to be a real `JSObjectLiteralExpression`, so e.g. arrow-function
     * bodies `const f = () => ({...})` never get converted.
     */
    fun objectLiteralSource(declText: String): String? {
        val eq = findTopLevelAssignment(declText) ?: return null
        val open = nextUnquotedChar(declText, eq + 1) { it == '{' } ?: return null
        val close = matchBalanced(declText, open) ?: return null
        return declText.substring(open, close + 1)
    }

    /** First top-level `=` (not `==`, `=>`, `<=`, `>=`, `!=`) — i.e. the assignment before the initializer. */
    private fun findTopLevelAssignment(s: String): Int? {
        var depth = 0
        var i = 0
        while (i < s.length) {
            when (val c = s[i]) {
                '"', '\'', '`' -> { i = skipString(s, i); continue }
                '{', '(', '[' -> depth++
                '}', ')', ']' -> depth--
                '=' -> {
                    if (depth == 0) {
                        val prev = if (i > 0) s[i - 1] else ' '
                        val next = if (i + 1 < s.length) s[i + 1] else ' '
                        if (prev != '=' && prev != '!' && prev != '<' && prev != '>' &&
                            next != '=' && next != '>'
                        ) return i
                    }
                }
            }
            i++
        }
        return null
    }

    private fun nextUnquotedChar(s: String, from: Int, pred: (Char) -> Boolean): Int? {
        var i = from
        while (i < s.length) {
            val c = s[i]
            if (c == '"' || c == '\'' || c == '`') { i = skipString(s, i); continue }
            if (pred(c)) return i
            i++
        }
        return null
    }

    /** Match a balanced `{...}` block starting at [open]; returns the index of the closing `}`. */
    private fun matchBalanced(s: String, open: Int): Int? {
        if (s[open] != '{') return null
        var depth = 0
        var i = open
        while (i < s.length) {
            when (val c = s[i]) {
                '"', '\'', '`' -> { i = skipString(s, i); continue }
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
            i++
        }
        return null
    }

    /** Advance [start] (which points at a quote) past the end of the quoted string. */
    private fun skipString(s: String, start: Int): Int {
        val q = s[start]
        var i = start + 1
        while (i < s.length) {
            val c = s[i]
            if (c == '\\') { i += 2; continue }
            if (c == q) return i + 1
            i++
        }
        return s.length
    }
}
