package com.github.reactunitconverter.service

import com.github.reactunitconverter.config.Px2RemConfigDetector
import com.github.reactunitconverter.model.Px2RemConfig
import com.intellij.openapi.components.*
import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.XmlSerializerUtil
import java.io.File

@State(
    name = "ReactUnitConverterProjectConfig",
    storages = [Storage("react-unit-converter-project.xml", roamingType = RoamingType.DISABLED)]
)
@Service(Service.Level.PROJECT)
class ProjectConfigService(val project: Project) : PersistentStateComponent<ProjectConfigService.State> {

    data class State(
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
        var lastDetectedAt: Long = 0L,
    ) {
        fun toConfig(): Px2RemConfig = Px2RemConfig(
            source = detectedSource,
            cssLevelPluginEnabled = cssLevelPluginEnabled,
            unitToConvert = unitToConvert,
            rootValue = rootValue,
            unitPrecision = unitPrecision,
            propList = propList.split(",").map { it.trim() }.filter { it.isNotEmpty() }.ifEmpty { listOf("*") },
            minPixelValue = minPixelValue,
            mediaQuery = false,
            viewportWidth = viewportWidth,
            viewportHeight = viewportHeight,
            selectorBlackList = emptyList(),
            replace = true,
            exclude = emptyList(),
        )
    }

    private val state = State()

    override fun getState(): State = state
    override fun loadState(s: State) = XmlSerializerUtil.copyBean(s, state)

    fun currentConfig(): Px2RemConfig {
        if (!state.overridden && (System.currentTimeMillis() - state.lastDetectedAt) > 60_000 * 10) {
            runCatching { redetect() }
        }
        return state.toConfig()
    }

    fun redetect(rootFile: File? = null) {
        val base = rootFile ?: project.basePath?.let { File(it) } ?: return
        val detector = Px2RemConfigDetector(base)
        val cfg = detector.detect()
        applyDetected(cfg)
    }

    private fun applyDetected(cfg: Px2RemConfig) {
        state.overridden = false
        state.detectedSource = cfg.source
        state.cssLevelPluginEnabled = cfg.cssLevelPluginEnabled
        state.unitToConvert = cfg.unitToConvert
        state.rootValue = cfg.rootValue
        state.unitPrecision = cfg.unitPrecision
        state.propList = cfg.propList.joinToString(", ")
        state.minPixelValue = cfg.minPixelValue
        state.viewportWidth = cfg.viewportWidth
        state.viewportHeight = cfg.viewportHeight
        state.lastDetectedAt = System.currentTimeMillis()
    }

    fun applyOverride(cfg: Px2RemConfig) {
        state.overridden = true
        state.detectedSource = cfg.source + " (user override)"
        state.cssLevelPluginEnabled = cfg.cssLevelPluginEnabled
        state.unitToConvert = cfg.unitToConvert
        state.rootValue = cfg.rootValue
        state.unitPrecision = cfg.unitPrecision
        state.propList = cfg.propList.joinToString(", ")
        state.minPixelValue = cfg.minPixelValue
        state.viewportWidth = cfg.viewportWidth
        state.viewportHeight = cfg.viewportHeight
        state.lastDetectedAt = System.currentTimeMillis()
    }

    companion object {
        @JvmStatic
        fun getInstance(project: Project): ProjectConfigService =
            project.getService(ProjectConfigService::class.java)
    }
}
