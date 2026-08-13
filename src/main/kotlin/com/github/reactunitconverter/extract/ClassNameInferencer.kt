package com.github.reactunitconverter.extract

/**
 * Infers a semantic CSS class name from context.
 *
 * Contexts used (in priority order):
 * 1. Existing className on the JSX element (if present, strip known structural prefixes)
 * 2. className of parent/sibling JSX elements
 * 3. The JSX tag itself (div, button, main, FormInput, etc.)
 * 4. Properties within the style object (dimensions, color, layout)
 */
object ClassNameInferencer {

    data class Context(
        val jsxTag: String = "div",
        val currentClassName: String? = null,  // `className="foo"` on the same element
        val parentClassName: String? = null,
        val siblingClassNames: List<String> = emptyList(),
        val ariaLabel: String? = null,
        val id: String? = null,
        val dataTestid: String? = null,
        val role: String? = null,
        val styleProps: Map<String, Any?> = emptyMap(),  // React.CSSProperties preview
        val existingClassNames: Set<String> = emptySet(),
    )

    /** Suggest a class name, guaranteed not to conflict with existing. */
    fun suggest(context: Context): String {
        val candidates = buildList {
            // 1) from existing className on this element: take last segment or meaningful part
            if (!context.currentClassName.isNullOrBlank()) {
                splitClassNames(context.currentClassName).forEach { add(it) }
            }
            // 2) id / data-testid / aria-label / role
            listOfNotNull(context.id, context.dataTestid, context.ariaLabel, context.role).forEach { add(slugify(it)) }

            // 3) parent + tag: e.g. "formContainer" -> "formContainerButton"
            if (!context.parentClassName.isNullOrBlank()) {
                for (seg in splitClassNames(context.parentClassName)) {
                    add(seg + capitalize(context.jsxTag))
                    add(seg)
                }
            }

            // 4) jsx tag alone
            add(context.jsxTag)

            // 5) style-property driven names
            val styleBased = styleBasedNames(context.styleProps)
            addAll(styleBased)

            // 6) sibling based patterns: if sibling has "cardHeader", suggest "cardBody" etc.
            context.siblingClassNames.forEach { sib ->
                for (seg in splitClassNames(sib)) {
                    val variant = inferVariant(seg)
                    if (variant != null) add(variant)
                }
            }
        }

        // Normalize, dedupe, remove generic words
        val normalized = candidates
            .map { sanitize(it) }
            .filter { it.isNotBlank() && it !in GENERIC_BLACKLIST }
            .distinct()

        // Ensure uniqueness vs existing class names
        for (name in normalized.ifEmpty { listOf("box") }) {
            var candidate = name
            var i = 2
            while (candidate in context.existingClassNames) {
                candidate = "$name$i"
                i++
            }
            return toCamelCase(candidate)
        }
        return "style_01"
    }

    // ----- utils

    private val GENERIC_BLACKLIST = setOf(
        "container", "wrapper", "box", "item", "element", "block", "node", "view", "root", "main",
        "section", "div", "span", "p"
    )

    private fun splitClassNames(cn: String): List<String> {
        // Split on spaces, dashes, underscores and camel case boundaries.
        val parts = cn.split(Regex("[\\s,_\\-]+")).flatMap { splitCamel(it) }.map { it.lowercase() }
        return parts.filter { it.isNotBlank() }
    }

    private fun splitCamel(s: String): List<String> {
        val out = mutableListOf<String>()
        val current = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c.isUpperCase() -> {
                    if (current.isNotEmpty()) {
                        // capture consecutive uppercases as one (e.g. "URLParser" -> ["URL", "Parser"])
                        val runStart = i
                        while (i < s.length && s[i].isUpperCase()) i++
                        val uppers = s.substring(runStart, i)
                        if (i < s.length && !s[i].isUpperCase() && uppers.length > 1) {
                            // last upper belongs to next word
                            current.append(uppers.dropLast(1))
                            out += current.toString()
                            current.clear()
                            current.append(uppers.last())
                            // don't inc i: s[i] is the first lowercase of next word
                            i--
                        } else {
                            if (current.isNotEmpty()) {
                                out += current.toString(); current.clear()
                            }
                            current.append(uppers.lowercase())
                        }
                    } else {
                        current.append(c.lowercase())
                    }
                }
                else -> current.append(c)
            }
            i++
        }
        if (current.isNotEmpty()) out += current.toString()
        return out.filter { it.isNotBlank() }
    }

    private fun slugify(raw: String): String =
        raw.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim().split(Regex("\\s+")).joinToString("-")

    private fun sanitize(s: String): String =
        s.replace(Regex("[^A-Za-z0-9\\-]+"), "").trim('-', '_')

    private fun capitalize(s: String): String =
        if (s.isEmpty()) s else s[0].uppercase() + s.substring(1)

    private fun toCamelCase(dashOrSpace: String): String {
        val parts = dashOrSpace.split(Regex("[\\-\\s_]+"))
        if (parts.isEmpty()) return "x"
        return parts[0].lowercase() + parts.drop(1).joinToString("") { capitalize(it.lowercase()) }
    }

    /** Emit variant-style names when the sibling has a common structural role. */
    private fun inferVariant(siblingSeg: String): String? {
        val s = siblingSeg.lowercase()
        return when {
            s.endsWith("header") -> s.removeSuffix("header") + "body"
            s.endsWith("body") -> s.removeSuffix("body") + "footer"
            s.endsWith("footer") -> s.removeSuffix("footer") + "content"
            s.endsWith("title") -> s.removeSuffix("title") + "subtitle"
            s.endsWith("left") -> s.removeSuffix("left") + "right"
            s.endsWith("right") -> s.removeSuffix("right") + "center"
            s.endsWith("top") -> s.removeSuffix("top") + "bottom"
            s.endsWith("bottom") -> s.removeSuffix("bottom") + "content"
            else -> null
        }
    }

    /** Generate class name fragments from the style properties present. */
    private fun styleBasedNames(props: Map<String, Any?>): List<String> {
        val hints = mutableListOf<String>()
        val has = mutableSetOf<String>()
        for ((k, v) in props) has += k.lowercase()

        // layout / display categories
        if ("display" in has) hints += when ((props["display"] as? String)?.lowercase()) {
            "flex" -> "flex"
            "grid" -> "grid"
            "block" -> "block"
            "inline-block" -> "inlineBlock"
            "none" -> "hidden"
            else -> null
        }.orEmpty()

        if (hasAny(has, "flexdirection", "flex-direction")) hints += "flexBox"
        if (hasAny(has, "justifycontent", "justify-content")) hints += "flex"
        if (hasAny(has, "alignitems", "align-items")) hints += "flex"

        // position based
        if ("position" in has) hints += when ((props["position"] as? String)?.lowercase()) {
            "absolute" -> "abs"
            "fixed" -> "fixed"
            "relative" -> "rel"
            "sticky" -> "sticky"
            else -> null
        }.orEmpty()

        // dimension based
        val w = (props["width"] as? String)?.let { pxNum(it) } ?: (props["width"] as? Number)?.toDouble()
        val h = (props["height"] as? String)?.let { pxNum(it) } ?: (props["height"] as? Number)?.toDouble()
        if (w != null && h != null) {
            hints += "size"
            if (w == h) hints += "square"
        } else if (w != null) hints += "w" + sizeTier(w)
        else if (h != null) hints += "h" + sizeTier(h)

        // padding / margin
        if (hasAnyPad(has, "padding")) hints += "padded"
        if (hasAnyPad(has, "margin")) hints += "spaced"

        // text / color
        if ("color" in has) hints += "colored"
        if (hasAny(has, "fontsize", "font-size")) hints += "text"
        if (hasAny(has, "fontweight", "font-weight")) hints += "text"
        if (hasAny(has, "textalign", "text-align")) hints += "text"
        if (hasAny(has, "background", "backgroundcolor")) hints += "bg"

        // border / radius
        if (hasAnyPad(has, "border")) hints += "bordered"
        if (hasAny(has, "borderradius", "border-radius")) hints += "rounded"

        // shadow
        if (hasAny(has, "boxshadow", "box-shadow")) hints += "shadow"
        if (hasAny(has, "filter")) hints += "fx"

        return hints.distinct()
    }

    private fun hasAny(set: Set<String>, vararg names: String) = names.any { it in set }
    private fun hasAnyPad(set: Set<String>, prefix: String): Boolean =
        set.any { it == prefix.lowercase() || it.startsWith(prefix.lowercase()) }

    private fun pxNum(s: String): Double? {
        val m = Regex("(-?\\d+(?:\\.\\d+)?)\\s*px").find(s) ?: return null
        return m.groupValues[1].toDoubleOrNull()
    }

    private fun sizeTier(n: Double): String = when {
        n < 16 -> "Xs"
        n < 32 -> "Sm"
        n < 64 -> "Md"
        n < 128 -> "Lg"
        n < 256 -> "Xl"
        else -> "Huge"
    }
}
