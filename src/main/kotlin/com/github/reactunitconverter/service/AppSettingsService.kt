package com.github.reactunitconverter.service

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.*
import com.intellij.util.xmlb.XmlSerializerUtil

@State(
    name = "ReactUnitConverterAppSettings",
    storages = [Storage("react-unit-converter-app.xml", roamingType = RoamingType.DEFAULT)]
)
@Service(Service.Level.APP)
class AppSettingsService : PersistentStateComponent<AppSettingsService.State> {

    data class State(
        var defaultUnitToConvert: String = "rem",
        var defaultRootValue: Double = 16.0,
        var defaultUnitPrecision: Int = 5,
        var defaultViewportWidth: Double = 750.0,
        var defaultMinPixelValue: Double = 0.0,
        var cssModuleImportName: String = "styles",
        var autoRenameAfterExtract: Boolean = true,
        var showCssPreviewInRenameDialog: Boolean = true,
        var warnAboutPostcssConflict: Boolean = true,
    )

    private val state = State()

    override fun getState(): State = state

    override fun loadState(s: State) = XmlSerializerUtil.copyBean(s, state)

    companion object {
        @JvmStatic
        fun getInstance(): AppSettingsService = ApplicationManager.getApplication().getService(AppSettingsService::class.java)
    }
}
