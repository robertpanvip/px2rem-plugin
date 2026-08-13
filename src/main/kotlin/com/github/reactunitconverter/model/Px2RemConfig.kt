package com.github.reactunitconverter.model

/**
 * Data model for px2rem / postcss-pxtorem configuration.
 * Mirrors the options exposed by:
 *  - postcss-pxtorem (https://github.com/cuth/postcss-pxtorem)
 *  - @rsbuild/plugin-px2rem (Rsbuild)
 *  - vite plugins like vite-plugin-px2rem
 */
data class Px2RemConfig(
    /** Source of the detected config. e.g. "vite.config.ts" / "postcss.config.js" / "rsbuild.config.ts" / "default" */
    val source: String = "default",
    /** True when the project already has a CSS-level px2rem (PostCSS) configured.
     *  In that case we skip CSS files and only touch React inline styles. */
    val cssLevelPluginEnabled: Boolean = false,
    /** Target unit to convert to: "rem" or "vw". */
    val unitToConvert: String = "rem",
    /** 1rem = rootValue px. Postcss-pxtorem default is 16. */
    val rootValue: Double = 16.0,
    /** Decimal digits kept after division. */
    val unitPrecision: Int = 5,
    /** CSS props that should be converted. "*" means all.
     *  A leading "!" means exclude, e.g. ["*", "!font-size"]. */
    val propList: List<String> = listOf("*"),
    /** Minimum pixel value to convert. Smaller px values are kept as is. */
    val minPixelValue: Double = 0.0,
    /** When true, also replace px in media queries (not used for inline styles). */
    val mediaQuery: Boolean = false,
    /** When unit is "vw", this is the design viewport width in px. Default 750. */
    val viewportWidth: Double = 750.0,
    /** When unit is "vw" - fallback design width for landscape. */
    val viewportHeight: Double = 1334.0,
    /** Selector blacklist - strings or regex strings. Match = skip (CSS only). */
    val selectorBlackList: List<String> = emptyList(),
    /** When true, replace rule values but also keep the original px line (CSS only). */
    val replace: Boolean = true,
    /** File / directory exclusions (regex strings). */
    val exclude: List<String> = emptyList(),
) {
    /** Returns true if the given CSS property name should be converted,
     *  based on propList semantics ("*", "!prop", wildcards with '*'). */
    fun isPropAllowed(propName: String): Boolean {
        val normalized = propName.trim()
        if (propList.isEmpty()) return true
        val allowAll = propList.any { it == "*" }
        val explicitInclude = propList.filter { !it.startsWith("!") && it != "*" }
        val explicitExclude = propList.filter { it.startsWith("!") }.map { it.removePrefix("!") }
        fun matches(pattern: String, input: String): Boolean {
            if (pattern == input) return true
            if (!pattern.contains("*")) return pattern.equals(input, ignoreCase = true)
            val regex = "^" + pattern.replace(".", "\\.").replace("*", ".*") + "$"
            return input.matches(Regex(regex, RegexOption.IGNORE_CASE))
        }
        val excluded = explicitExclude.any { matches(it, normalized) }
        if (excluded) return false
        if (allowAll) return true
        return explicitInclude.any { matches(it, normalized) }
    }

    companion object {
        val DEFAULT: Px2RemConfig = Px2RemConfig()
    }
}
