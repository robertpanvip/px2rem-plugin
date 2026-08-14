package com.github.reactunitconverter.service

/**
 * Headless, IDE-free equivalent of [ProjectConfigService.State].
 * Exposes the same `propList` parsing + unit/rootValue/viewport logic via [toConfig]
 * so tests can run without the IntelliJ Platform SDK on classpath.
 *
 * When the project has no auto-detected config (`detectedSource == "default"`) and the
 * user hasn't overridden it, the conversion parameters fall back to the app-level
 * global defaults ([AppSettingsService.State]) instead of the hardcoded project defaults
 * (Bug #6). Those app defaults are carried here as `appDefault*` fields so the logic
 * stays headless-testable.
 */
data class ProjectConfigState(
    var overridden: Boolean = false,
    var unitToConvert: String = "rem",
    var rootValue: Double = 16.0,
    var unitPrecision: Int = 5,
    var propList: String = "*",
    var minPixelValue: Double = 0.0,
    var viewportWidth: Double = 750.0,
    var viewportHeight: Double = 1334.0,
    var cssLevelPluginEnabled: Boolean = false,
    var detectedSource: String = "default",
    // --- app-level global defaults (mirrors AppSettingsService.State) ---
    var appDefaultUnitToConvert: String = "rem",
    var appDefaultRootValue: Double = 16.0,
    var appDefaultUnitPrecision: Int = 5,
    var appDefaultViewportWidth: Double = 750.0,
    var appDefaultMinPixelValue: Double = 0.0,
) {
    fun toConfig(): com.github.reactunitconverter.model.Px2RemConfig {
        // Bug #6: a project with no detection and no override must use the user's
        // global defaults rather than the hardcoded project ones.
        val useAppDefaults = !overridden && detectedSource == "default"
        val unit = if (useAppDefaults) appDefaultUnitToConvert else unitToConvert
        val root = if (useAppDefaults) appDefaultRootValue else rootValue
        val precision = if (useAppDefaults) appDefaultUnitPrecision else unitPrecision
        val minPx = if (useAppDefaults) appDefaultMinPixelValue else minPixelValue
        val vw = if (useAppDefaults) appDefaultViewportWidth else viewportWidth
        return com.github.reactunitconverter.model.Px2RemConfig(
            unitToConvert = unit,
            rootValue = root,
            unitPrecision = precision,
            propList = propList.split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .ifEmpty { listOf("*") },
            minPixelValue = minPx,
            viewportWidth = vw,
            viewportHeight = viewportHeight,
            cssLevelPluginEnabled = cssLevelPluginEnabled,
            source = detectedSource,
        )
    }
}
