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
        fun toConfig(): Px2RemConfig {
            // Bug #6: with no auto-detection ("default") and no user override, fall back to the
            // app-level global defaults configured in Settings → React Unit Converter, instead of
            // the hardcoded project defaults (rem / 16 / 5 / 750 / 0).
            val useAppDefaults = !overridden && detectedSource == "default"
            val app = if (useAppDefaults) {
                try { AppSettingsService.getInstance().state } catch (_: Throwable) { null }
            } else null
            val unit = app?.defaultUnitToConvert ?: unitToConvert
            val root = app?.defaultRootValue ?: rootValue
            val precision = app?.defaultUnitPrecision ?: unitPrecision
            val minPx = app?.defaultMinPixelValue ?: minPixelValue
            val vw = app?.defaultViewportWidth ?: viewportWidth
            return Px2RemConfig(
                source = detectedSource,
                cssLevelPluginEnabled = cssLevelPluginEnabled,
                unitToConvert = unit,
                rootValue = root,
                unitPrecision = precision,
                propList = propList.split(",").map { it.trim() }.filter { it.isNotEmpty() }.ifEmpty { listOf("*") },
                minPixelValue = minPx,
                mediaQuery = false,
                viewportWidth = vw,
                viewportHeight = viewportHeight,
                selectorBlackList = emptyList(),
                replace = true,
                exclude = emptyList(),
            )
        }
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
