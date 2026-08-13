package com.github.reactunitconverter.config

import com.github.reactunitconverter.model.Px2RemConfig
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.yaml.snakeyaml.Yaml
import java.io.File

/**
 * Detects px2rem / postcss-pxtorem configuration in a project.
 * Supported sources:
 *   1) PostCSS: postcss.config.js / .postcssrc / postcss.config.cjs / postcss.config.mjs
 *      with "postcss-pxtorem" plugin defined.
 *   2) Vite: vite.config.js / vite.config.ts / vite.config.mjs / vite.config.cjs
 *      which contains a px2rem() plugin invocation (vite-plugin-px2rem) OR
 *      css.postcss.plugins pxtorem reference.
 *   3) Rsbuild: rsbuild.config.js / ts / cjs / mjs with @rsbuild/plugin-px2rem options.
 *   4) Legacy: .px2remrc / px2rem.config.js / package.json "px2rem" field
 *
 * When cssLevelPluginEnabled=true (i.e. a PostCSS pxtorem is installed), the plugin
 * intentionally skips CSS file rewriting and only processes React inline styles.
 */
class Px2RemConfigDetector(
    private val projectRoot: File,
    private val gson: Gson = Gson(),
) {

    fun detect(): Px2RemConfig {
        val sources = mutableListOf<Pair<String, Px2RemConfig?>>()

        val postcss = detectFromPostcss()
        if (postcss != null) sources += "postcss" to postcss

        val vite = detectFromVite()
        if (vite != null) sources += "vite" to vite

        val rsbuild = detectFromRsbuild()
        if (rsbuild != null) sources += "rsbuild" to rsbuild

        val packageJson = detectFromPackageJson()
        if (packageJson != null) sources += "package.json" to packageJson

        val rc = detectFromRc()
        if (rc != null) sources += "px2remrc" to rc

        if (sources.isEmpty()) return Px2RemConfig.DEFAULT.copy(source = "default")

        // Precedence order (user explicitly opts-in via PostCSS wins for cssLevelPluginEnabled)
        // Merge values: the first non-default wins for each field, but OR for booleans.
        var merged = Px2RemConfig.DEFAULT
        val mergedSource = sources.joinToString(",") { it.first }
        var anyCssPlugin = false
        for ((_, cfg) in sources) {
            if (cfg == null) continue
            if (cfg.cssLevelPluginEnabled) anyCssPlugin = true
            merged = merged.mergeOverwriteDefaults(cfg)
        }
        return merged.copy(source = mergedSource, cssLevelPluginEnabled = anyCssPlugin)
    }

    // ---- PostCSS config detection ----
    private fun detectFromPostcss(): Px2RemConfig? {
        val candidates = listOf(
            "postcss.config.js", "postcss.config.cjs", "postcss.config.mjs",
            "postcss.config.ts", ".postcssrc", ".postcssrc.js", ".postcssrc.json", ".postcssrc.yaml", ".postcssrc.yml"
        )
        for (name in candidates) {
            val f = File(projectRoot, name)
            if (!f.exists()) continue
            val text = f.readTextSafe() ?: continue
            return parsePostcssLike(text, source = name)
        }
        return null
    }

    private fun parsePostcssLike(text: String, source: String): Px2RemConfig? {
        // 1) Try as JSON (for .json / .postcssrc)
        val jsonCfg: JsonObject? = try {
            JsonParser.parseString(text)?.takeIf { it.isJsonObject }?.asJsonObject
        } catch (_: Throwable) { null }
        if (jsonCfg != null) {
            val plugins = jsonCfg.get("plugins")
            val opts = findPxtoremPluginOpts(plugins)
            if (opts != null) return opts.toConfig(source = source, cssLevelPluginEnabled = true)
        }
        // 2) JavaScript/TypeScript/Object literal: use regex to find postcss-pxtorem plugin block
        return extractPluginOptsJsText(text, pluginNames = listOf("postcss-pxtorem", "pxtorem", "px2rem"))
            ?.toConfig(source = source, cssLevelPluginEnabled = true)
    }

    // ---- Vite detection ----
    private fun detectFromVite(): Px2RemConfig? {
        val candidates = listOf("vite.config.js", "vite.config.ts", "vite.config.mjs", "vite.config.cjs")
        for (name in candidates) {
            val f = File(projectRoot, name)
            if (!f.exists()) continue
            val text = f.readTextSafe() ?: continue
            // Case A: direct px2rem plugin import + call in plugins[]
            val plugin = extractPluginOptsJsText(
                text,
                pluginNames = listOf(
                    "vite-plugin-px2rem", "px2rem",
                    "postcssPluginPx2rem", "postcss-px-to-viewport"
                )
            )
            if (plugin != null) {
                // if it's a "px2rem(...)" inside vite plugins[], it counts as CSS-level transform.
                val cssLevel = !text.contains("inline")
                return plugin.toConfig(source = name, cssLevelPluginEnabled = cssLevel)
            }
            // Case B: css.postcss.plugins references pxtorem with options
            val inner = extractPluginOptsJsText(text, listOf("postcss-pxtorem", "pxtorem"))
            if (inner != null) return inner.toConfig(source = name, cssLevelPluginEnabled = true)
        }
        return null
    }

    // ---- Rsbuild detection ----
    private fun detectFromRsbuild(): Px2RemConfig? {
        val candidates = listOf("rsbuild.config.js", "rsbuild.config.ts", "rsbuild.config.mjs", "rsbuild.config.cjs")
        for (name in candidates) {
            val f = File(projectRoot, name)
            if (!f.exists()) continue
            val text = f.readTextSafe() ?: continue
            val opts = extractPluginOptsJsText(
                text,
                pluginNames = listOf("@rsbuild/plugin-px2rem", "pluginPx2rem", "px2rem")
            )
            if (opts != null) {
                return opts.toConfig(source = name, cssLevelPluginEnabled = text.contains("plugins") && !text.contains("inline"))
            }
        }
        return null
    }

    // ---- package.json ----
    private fun detectFromPackageJson(): Px2RemConfig? {
        val f = File(projectRoot, "package.json")
        if (!f.exists()) return null
        val text = f.readTextSafe() ?: return null
        val json = try { JsonParser.parseString(text).asJsonObject } catch (_: Throwable) { return null }
        // field "px2rem" OR postcss.pxtorem
        val direct = json.getAsJsonObject("px2rem")
        if (direct != null) return rawMapToConfig(direct.asStringMap(), "package.json", cssLevelPluginEnabled = true)
        val postcss = json.getAsJsonObject("postcss")
        val p = postcss?.get("plugins")
        val opts = findPxtoremPluginOpts(p)
        if (opts != null) return opts.toConfig("package.json", cssLevelPluginEnabled = true)
        // Check devDependencies
        val dev = json.getAsJsonObject("devDependencies") ?: json.getAsJsonObject("dependencies")
        if (dev != null) {
            val has = setOf("postcss-pxtorem", "@rsbuild/plugin-px2rem", "vite-plugin-px2rem").any { dev.has(it) }
            if (has) return Px2RemConfig.DEFAULT.copy(source = "package.json(deps)", cssLevelPluginEnabled = true)
        }
        return null
    }

    private fun detectFromRc(): Px2RemConfig? {
        for (name in listOf(".px2remrc", ".px2remrc.json", "px2rem.config.js")) {
            val f = File(projectRoot, name)
            if (!f.exists()) continue
            val text = f.readTextSafe() ?: continue
            val json = try { JsonParser.parseString(text)?.takeIf { it.isJsonObject }?.asJsonObject } catch (_: Throwable) { null }
            if (json != null) return rawMapToConfig(json.asStringMap(), name, cssLevelPluginEnabled = true)
            val opts = extractPluginOptsJsText(text, listOf("px2rem"))
            if (opts != null) return opts.toConfig(name, cssLevelPluginEnabled = true)
        }
        // YAML rc
        for (name in listOf(".px2remrc.yaml", ".px2remrc.yml")) {
            val f = File(projectRoot, name)
            if (!f.exists()) continue
            val text = f.readTextSafe() ?: continue
            val map: Map<*, *>? = try { Yaml().load(text) } catch (_: Throwable) { null }
            if (map is Map<*, *>) return rawMapToConfig(map.asStringMap(), name, cssLevelPluginEnabled = true)
        }
        return null
    }

    // ---- Helpers ----
    private fun File.readTextSafe(): String? = runCatching { readText(Charsets.UTF_8) }.getOrNull()

    private fun findPxtoremPluginOpts(plugins: JsonElement?): RawOpts? {
        if (plugins == null) return null
        // plugins: { "postcss-pxtorem": {...} }
        if (plugins.isJsonObject) {
            for (key in listOf("postcss-pxtorem", "pxtorem", "px2rem")) {
                val v = plugins.asJsonObject.get(key)
                if (v != null && v.isJsonObject) return RawOpts.from(v.asJsonObject.asStringMap())
                if (v != null && !v.isJsonNull) return RawOpts() // plugin enabled with default options
            }
        }
        // plugins: [ require('postcss-pxtorem')({...}) ]  (can't parse via JSON; skipped)
        return null
    }

    /**
     * Very permissive JS/TS object-literal parser.
     * Finds a call like `px2rem({ rootValue: 75, propList: ['*'] })`
     * and extracts the first object argument as a [RawOpts].
     */
    private fun extractPluginOptsJsText(text: String, pluginNames: List<String>): RawOpts? {
        // Flatten: remove comments first (/* */ and //)
        val cleaned = text
            .replace(Regex("/\\*[\\s\\S]*?\\*/"), "")
            .replace(Regex("//[^\\n]*"), "")

        for (name in pluginNames) {
            // Try patterns:
            //  name({ ... })
            //  require('name')({ ... })
            //  name: { ... }
            val callRegex = Regex(
                """(?:require\s*\(\s*['"]${Regex.escape(name)}['"]\s*\)|${Regex.escape(name)})\s*\(\s*(\{""",
                RegexOption.IGNORE_CASE
            )
            val m = callRegex.find(cleaned)
            if (m != null) {
                val start = m.range.last - 1 // index of '{'
                val end = matchBalancedBraces(cleaned, start)
                if (end != -1) {
                    val block = cleaned.substring(start, end + 1)
                    val map = parseJsObjectLiteral(block)
                    return RawOpts.from(map)
                }
            }
            // Object field form:  { ..., name: { ... } }
            val fieldRegex = Regex("""['"]?${Regex.escape(name)}['"]?\s*:\s*\{""")
            val mf = fieldRegex.find(cleaned)
            if (mf != null) {
                val start = mf.range.last
                val end = matchBalancedBraces(cleaned, start)
                if (end != -1) {
                    val block = cleaned.substring(start, end + 1)
                    val map = parseJsObjectLiteral(block)
                    return RawOpts.from(map)
                }
            }
        }
        return null
    }

    private fun matchBalancedBraces(s: String, openIdx: Int): Int {
        if (s[openIdx] != '{') return -1
        var depth = 0
        var i = openIdx
        while (i < s.length) {
            val c = s[i]
            if (c == '{') depth++
            else if (c == '}') {
                depth--
                if (depth == 0) return i
            } else if (c == '"' || c == '\'' || c == '`') {
                i = skipString(s, i)
                continue
            }
            i++
        }
        return -1
    }

    private fun skipString(s: String, start: Int): Int {
        val quote = s[start]
        var i = start + 1
        while (i < s.length) {
            if (s[i] == '\\') { i += 2; continue }
            if (s[i] == quote) return i
            i++
        }
        return s.length - 1
    }

    /**
     * Converts a JS object literal like `{ rootValue: 75, propList: ["*"], exclude: /node_modules/ }`
     * into a flat Map<String, Any?> suitable for config extraction.
     * Does NOT handle arbitrary JS, but handles what px2rem configs typically contain.
     */
    private fun parseJsObjectLiteral(block: String): Map<String, Any?> {
        val result = LinkedHashMap<String, Any?>()
        var i = 0
        if (block.isNotEmpty() && block[0] == '{') i = 1
        val end = block.length
        while (i < end) {
            // skip whitespace / commas
            while (i < end && block[i].let { it == ',' || it.isWhitespace() }) i++
            if (i >= end || block[i] == '}') break
            // key: quoted or identifier
            val key: String
            val c = block[i]
            if (c == '"' || c == '\'' || c == '`') {
                val endStr = skipString(block, i)
                key = block.substring(i + 1, endStr)
                i = endStr + 1
            } else {
                val sb = StringBuilder()
                while (i < end && block[i].let { it.isLetterOrDigit() || it == '_' || it == '-' || it == '$' }) {
                    sb.append(block[i]); i++
                }
                key = sb.toString()
            }
            // colon
            while (i < end && block[i].isWhitespace()) i++
            if (i < end && block[i] == ':') i++
            while (i < end && block[i].isWhitespace()) i++
            // value
            val (value, consumed) = parseJsValue(block, i)
            result[key] = value
            i += consumed
        }
        return result
    }

    /** Parses a JS value (at position i) and returns (value, charsConsumed). */
    private fun parseJsValue(s: String, i: Int): Pair<Any?, Int> {
        var pos = i
        while (pos < s.length && s[pos].isWhitespace()) pos++
        if (pos >= s.length) return null to (pos - i)
        return when (s[pos]) {
            '{' -> {
                val end = matchBalancedBraces(s, pos)
                val inner = s.substring(pos, end + 1)
                parseJsObjectLiteral(inner) to (end - pos + 1)
            }
            '[' -> {
                // array
                val items = mutableListOf<Any?>()
                pos++
                var depth = 1
                while (pos < s.length && depth > 0) {
                    while (pos < s.length && s[pos].isWhitespace()) pos++
                    if (pos >= s.length) break
                    when (s[pos]) {
                        ']' -> { depth--; pos++ }
                        ',' -> { pos++ }
                        else -> {
                            val (v, c) = parseJsValue(s, pos)
                            items.add(v); pos += c
                        }
                    }
                }
                items to (pos - i)
            }
            '"', '\'', '`' -> {
                val end = skipString(s, pos)
                val value = s.substring(pos + 1, end)
                value to (end - i + 1)
            }
            '/', '-', '+', '.', in '0'..'9' -> {
                // number OR regex
                if (s[pos] == '/') {
                    val end = scanRegexEnd(s, pos)
                    val regex = s.substring(pos, end + 1)
                    return regex to (end - i + 1)
                }
                val sb = StringBuilder()
                if (s[pos] == '-' || s[pos] == '+') { sb.append(s[pos]); pos++ }
                while (pos < s.length && s[pos].let { it.isDigit() || it == '.' || it == 'e' || it == 'E' || it == '-' || it == '+' }) {
                    sb.append(s[pos]); pos++
                }
                val str = sb.toString()
                val num = str.toDoubleOrNull()
                (num ?: str) to (pos - i)
            }
            else -> {
                // identifier: true/false/null/undefined or function()...
                val sb = StringBuilder()
                while (pos < s.length && s[pos].let { it.isLetterOrDigit() || it == '_' || it == '$' }) {
                    sb.append(s[pos]); pos++
                }
                val ident = sb.toString()
                when (ident) {
                    "true" -> true to (pos - i)
                    "false" -> false to (pos - i)
                    "null", "undefined" -> null to (pos - i)
                    else -> ident to (pos - i)
                }
            }
        }
    }

    private fun scanRegexEnd(s: String, start: Int): Int {
        var i = start + 1
        while (i < s.length) {
            val c = s[i]
            if (c == '\\') { i += 2; continue }
            if (c == '[') {
                i++
                while (i < s.length) {
                    if (s[i] == '\\') { i += 2; continue }
                    if (s[i] == ']') break
                    i++
                }
            }
            if (c == '/') return i
            i++
        }
        return s.length - 1
    }

    private fun JsonObject.asStringMap(): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        for ((k, v) in entrySet()) out[k] = jsonElementToAny(v)
        return out
    }

    private fun Map<*, *>.asStringMap(): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        for ((k, v) in this) out[k.toString()] = v
        return out
    }

    private fun jsonElementToAny(el: JsonElement?): Any? = when {
        el == null || el.isJsonNull -> null
        el.isJsonPrimitive -> {
            val p = el.asJsonPrimitive
            when {
                p.isBoolean -> p.asBoolean
                p.isNumber -> p.asNumber.let { if (it.toDouble() == it.toLong().toDouble()) it.toLong() else it.toDouble() }
                else -> p.asString
            }
        }
        el.isJsonArray -> el.asJsonArray.mapTo(ArrayList()) { jsonElementToAny(it) }
        el.isJsonObject -> el.asJsonObject.asStringMap()
        else -> null
    }

    data class RawOpts(
        val unitToConvert: String? = null,
        val rootValue: Double? = null,
        val unitPrecision: Int? = null,
        val propList: List<String>? = null,
        val minPixelValue: Double? = null,
        val mediaQuery: Boolean? = null,
        val viewportWidth: Double? = null,
        val viewportHeight: Double? = null,
        val selectorBlackList: List<String>? = null,
        val replace: Boolean? = null,
        val exclude: List<String>? = null,
    ) {
        fun toConfig(source: String, cssLevelPluginEnabled: Boolean): Px2RemConfig = Px2RemConfig(
            source = source,
            cssLevelPluginEnabled = cssLevelPluginEnabled,
            unitToConvert = unitToConvert?.takeIf { it.isNotBlank() } ?: when {
                viewportWidth != null -> "vw"
                else -> "rem"
            },
            rootValue = rootValue ?: 16.0,
            unitPrecision = unitPrecision ?: 5,
            propList = propList ?: listOf("*"),
            minPixelValue = minPixelValue ?: 0.0,
            mediaQuery = mediaQuery ?: false,
            viewportWidth = viewportWidth ?: 750.0,
            viewportHeight = viewportHeight ?: 1334.0,
            selectorBlackList = selectorBlackList ?: emptyList(),
            replace = replace ?: true,
            exclude = exclude ?: emptyList(),
        )

        companion object {
            fun from(map: Map<String, Any?>): RawOpts = RawOpts(
                unitToConvert = map.str("unitToConvert") ?: map.str("unit"),
                rootValue = map.dbl("rootValue") ?: map.dbl("root_value"),
                unitPrecision = map.int("unitPrecision") ?: map.int("propWhiteList") ?: 5,
                propList = map.listStr("propList") ?: map.listStr("propWhiteList"),
                minPixelValue = map.dbl("minPixelValue") ?: map.dbl("min_value"),
                mediaQuery = map.bool("mediaQuery"),
                viewportWidth = map.dbl("viewportWidth") ?: map.dbl("designWidth"),
                viewportHeight = map.dbl("viewportHeight") ?: map.dbl("designHeight"),
                selectorBlackList = map.listStr("selectorBlackList") ?: map.listStr("selector_black_list"),
                replace = map.bool("replace"),
                exclude = map.listStr("exclude")
            )
        }
    }

    private fun Map<String, Any?>.str(k: String): String? = (this[k] as? String)?.takeIf { it.isNotBlank() }
    private fun Map<String, Any?>.dbl(k: String): Double? = when (val v = this[k]) {
        is Number -> v.toDouble()
        is String -> v.toDoubleOrNull()
        else -> null
    }
    private fun Map<String, Any?>.int(k: String): Int? = when (val v = this[k]) {
        is Number -> v.toInt()
        is String -> v.toIntOrNull()
        else -> null
    }
    private fun Map<String, Any?>.bool(k: String): Boolean? = when (val v = this[k]) {
        is Boolean -> v
        is String -> v.toBooleanStrictOrNull()
        else -> null
    }
    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.listStr(k: String): List<String>? {
        val v = this[k] ?: return null
        if (v is List<*>) return v.mapNotNull { it?.toString()?.takeIf { s -> s.isNotBlank() } }
        if (v is String) return listOf(v)
        if (v is Array<*>) return v.mapNotNull { it?.toString()?.takeIf { s -> s.isNotBlank() } }
        return null
    }

    private fun Px2RemConfig.mergeOverwriteDefaults(other: Px2RemConfig): Px2RemConfig = copy(
        unitToConvert = if (other.unitToConvert != DEFAULT.unitToConvert) other.unitToConvert else this.unitToConvert,
        rootValue = if (other.rootValue != DEFAULT.rootValue) other.rootValue else this.rootValue,
        unitPrecision = if (other.unitPrecision != DEFAULT.unitPrecision) other.unitPrecision else this.unitPrecision,
        propList = if (other.propList != DEFAULT.propList) other.propList else this.propList,
        minPixelValue = if (other.minPixelValue != DEFAULT.minPixelValue) other.minPixelValue else this.minPixelValue,
        mediaQuery = this.mediaQuery || other.mediaQuery,
        viewportWidth = if (other.viewportWidth != DEFAULT.viewportWidth) other.viewportWidth else this.viewportWidth,
        viewportHeight = if (other.viewportHeight != DEFAULT.viewportHeight) other.viewportHeight else this.viewportHeight,
        selectorBlackList = (this.selectorBlackList + other.selectorBlackList).distinct(),
        replace = this.replace && other.replace,
        exclude = (this.exclude + other.exclude).distinct(),
    )
}
