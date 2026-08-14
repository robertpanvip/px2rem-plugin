package com.github.reactunitconverter.service

/**
 * Headless, IDE-free equivalent of [ProjectConfigService.State].
 * Exposes the same `propList` parsing + unit/rootValue/viewport logic via [toConfig]
 * so tests can run without the IntelliJ Platform SDK on classpath.
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
) {
    fun toConfig(): com.github.reactunitconverter.model.Px2RemConfig =
        com.github.reactunitconverter.model.Px2RemConfig(
            unitToConvert = unitToConvert,
            rootValue = rootValue,
            unitPrecision = unitPrecision,
            propList = propList.split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .ifEmpty { listOf("*") },
            minPixelValue = minPixelValue,
            viewportWidth = viewportWidth,
            viewportHeight = viewportHeight,
            cssLevelPluginEnabled = cssLevelPluginEnabled,
            source = detectedSource,
        )
}
