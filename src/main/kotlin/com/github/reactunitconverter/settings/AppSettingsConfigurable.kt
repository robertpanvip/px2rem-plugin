package com.github.reactunitconverter.settings

import com.github.reactunitconverter.service.AppSettingsService
import com.intellij.openapi.options.BaseConfigurable
import com.intellij.ui.JBIntSpinner
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Application-level (global) settings: default conversion parameters when a project
 * has no px2rem config, CSS Module import name, and general UI behaviour.
 */
class AppSettingsConfigurable : BaseConfigurable("React Unit Converter") {

    private lateinit var unitField: JBTextField
    private lateinit var rootValue: JBTextField
    private lateinit var unitPrecision: JBIntSpinner
    private lateinit var vwWidth: JBTextField
    private lateinit var minPixelValue: JBTextField
    private lateinit var cssImportName: JBTextField
    private lateinit var autoRename: JBCheckBox
    private lateinit var showCssPreview: JBCheckBox
    private lateinit var warnConflict: JBCheckBox

    private var panel: JPanel? = null

    override fun createComponent(): JComponent {
        val state = AppSettingsService.getInstance().state
        unitField = JBTextField(state.defaultUnitToConvert, 10)
        rootValue = JBTextField(state.defaultRootValue.toString(), 10)
        unitPrecision = JBIntSpinner(state.defaultUnitPrecision, 0, 12, 1)
        vwWidth = JBTextField(state.defaultViewportWidth.toString(), 10)
        minPixelValue = JBTextField(state.defaultMinPixelValue.toString(), 10)
        cssImportName = JBTextField(state.cssModuleImportName, 12)
        autoRename = JBCheckBox("Prompt to rename class after extract to CSS Module", state.autoRenameAfterExtract)
        showCssPreview = JBCheckBox("Show CSS preview in rename dialog", state.showCssPreviewInRenameDialog)
        warnConflict = JBCheckBox("Show info when PostCSS pxtorem plugin is detected in project", state.warnAboutPostcssConflict)

        panel = FormBuilder.createFormBuilder()
            .addLabeledComponent(JBLabel("Default target unit (rem / vw / vh / em):"), unitField)
            .addLabeledComponent(JBLabel("Default rootValue (1 rem = N px):"), rootValue)
            .addLabeledComponent(JBLabel("Default precision (decimal digits):"), unitPrecision)
            .addLabeledComponent(JBLabel("Default viewportWidth (for vw unit, px):"), vwWidth)
            .addLabeledComponent(JBLabel("Default minPixelValue (smaller px unchanged):"), minPixelValue)
            .addSeparator()
            .addLabeledComponent(JBLabel("CSS Module import name (e.g. `styles` in import styles from 'x.module.css'):"), cssImportName)
            .addComponent(autoRename)
            .addComponent(showCssPreview)
            .addComponent(warnConflict)
            .panel
        return panel!!
    }

    override fun isModified(): Boolean {
        val s = AppSettingsService.getInstance().state
        return s.defaultUnitToConvert != unitField.text.trim() ||
                s.defaultRootValue != (rootValue.text.toDoubleOrNull() ?: 0.0) ||
                s.defaultUnitPrecision != unitPrecision.number.toInt() ||
                s.defaultViewportWidth != (vwWidth.text.toDoubleOrNull() ?: 0.0) ||
                s.defaultMinPixelValue != (minPixelValue.text.toDoubleOrNull() ?: 0.0) ||
                s.cssModuleImportName != cssImportName.text.trim() ||
                s.autoRenameAfterExtract != autoRename.isSelected ||
                s.showCssPreviewInRenameDialog != showCssPreview.isSelected ||
                s.warnAboutPostcssConflict != warnConflict.isSelected
    }

    override fun apply() {
        val s = AppSettingsService.getInstance().state
        s.defaultUnitToConvert = unitField.text.trim().ifBlank { "rem" }
        s.defaultRootValue = (rootValue.text.toDoubleOrNull() ?: 16.0).coerceAtLeast(1.0)
        s.defaultUnitPrecision = unitPrecision.number.toInt()
        s.defaultViewportWidth = (vwWidth.text.toDoubleOrNull() ?: 750.0).coerceAtLeast(1.0)
        s.defaultMinPixelValue = (minPixelValue.text.toDoubleOrNull() ?: 0.0).coerceAtLeast(0.0)
        s.cssModuleImportName = cssImportName.text.trim().ifBlank { "styles" }
        s.autoRenameAfterExtract = autoRename.isSelected
        s.showCssPreviewInRenameDialog = showCssPreview.isSelected
        s.warnAboutPostcssConflict = warnConflict.isSelected
    }

    override fun reset() {
        val s = AppSettingsService.getInstance().state
        unitField.text = s.defaultUnitToConvert
        rootValue.text = s.defaultRootValue.toString()
        unitPrecision.number = s.defaultUnitPrecision
        vwWidth.text = s.defaultViewportWidth.toString()
        minPixelValue.text = s.defaultMinPixelValue.toString()
        cssImportName.text = s.cssModuleImportName
        autoRename.isSelected = s.autoRenameAfterExtract
        showCssPreview.isSelected = s.showCssPreviewInRenameDialog
        warnConflict.isSelected = s.warnAboutPostcssConflict
    }

    override fun disposeUIResources() { panel = null }
}
