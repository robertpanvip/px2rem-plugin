package com.github.reactunitconverter.settings

import com.github.reactunitconverter.model.Px2RemConfig
import com.github.reactunitconverter.service.ProjectConfigService
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.options.BaseConfigurable
import com.intellij.openapi.project.Project
import com.intellij.ui.JBIntSpinner
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Per-project settings. Shows the last auto-detected px2rem config and lets the user
 * override specific values, or re-detect from Vite/Rsbuild/PostCSS config files.
 */
class ProjectSettingsConfigurable(private val project: Project) : BaseConfigurable("React Unit Converter Project") {

    private lateinit var overrideBox: JBCheckBox
    private lateinit var detectedLabel: JBLabel
    private lateinit var unitField: JBTextField
    private lateinit var rootValue: JBTextField
    private lateinit var unitPrecision: JBIntSpinner
    private lateinit var vwWidth: JBTextField
    private lateinit var vwHeight: JBTextField
    private lateinit var minPixelValue: JBTextField
    private lateinit var propListArea: JBTextArea
    private lateinit var cssLevelPlugin: JBCheckBox
    private lateinit var panel: JPanel

    override fun createComponent(): JComponent {
        val svc = ProjectConfigService.getInstance(project)
        val st = svc.state

        detectedLabel = JBLabel()
        refreshDetectedLabel(st.detectedSource, st.cssLevelPluginEnabled, st.lastDetectedAt)

        overrideBox = JBCheckBox("Override auto-detected values", st.overridden)
        unitField = JBTextField(st.unitToConvert, 10)
        rootValue = JBTextField(st.rootValue.toString(), 10)
        unitPrecision = JBIntSpinner(st.unitPrecision, 0, 12, 1)
        vwWidth = JBTextField(st.viewportWidth.toString(), 10)
        vwHeight = JBTextField(st.viewportHeight.toString(), 10)
        minPixelValue = JBTextField(st.minPixelValue.toString(), 10)
        propListArea = JBTextArea(st.propList, 3, 40).apply { lineWrap = true; wrapStyleWord = true }
        cssLevelPlugin = JBCheckBox("Project uses postcss-pxtorem (skip CSS files, only inline styles are modified)", st.cssLevelPluginEnabled)

        val redetectAction = object : AnAction("Re-detect from project", "Re-read px2rem config from Vite/Rsbuild/PostCSS", AllIcons.Actions.Refresh) {
            override fun actionPerformed(e: AnActionEvent) {
                svc.redetect()
                val s2 = svc.state
                overrideBox.isSelected = s2.overridden
                refreshDetectedLabel(s2.detectedSource, s2.cssLevelPluginEnabled, s2.lastDetectedAt)
                unitField.text = s2.unitToConvert
                rootValue.text = s2.rootValue.toString()
                unitPrecision.number = s2.unitPrecision
                vwWidth.text = s2.viewportWidth.toString()
                vwHeight.text = s2.viewportHeight.toString()
                minPixelValue.text = s2.minPixelValue.toString()
                propListArea.text = s2.propList
                cssLevelPlugin.isSelected = s2.cssLevelPluginEnabled
            }
        }

        val infoPanel = JPanel(BorderLayout()).apply {
            add(detectedLabel, BorderLayout.CENTER)
        }
        val toolbar = ToolbarDecorator.createDecorator(infoPanel)
            .addAction(redetectAction)
            .createPanel()

        val form = FormBuilder.createFormBuilder()
            .addComponent(toolbar)
            .addComponent(overrideBox)
            .addSeparator()
            .addLabeledComponent(JBLabel("Target unit (rem / vw / vh / em):"), unitField)
            .addLabeledComponent(JBLabel("rootValue (1rem = N px):"), rootValue)
            .addLabeledComponent(JBLabel("Precision (decimal digits):"), unitPrecision)
            .addLabeledComponent(JBLabel("viewportWidth (vw base, px):"), vwWidth)
            .addLabeledComponent(JBLabel("viewportHeight (vh base, px):"), vwHeight)
            .addLabeledComponent(JBLabel("minPixelValue (smaller px kept as-is):"), minPixelValue)
            .addLabeledComponent(JBLabel("propList (comma-separated, !prop = exclude, * = wildcard):"), propListArea)
            .addComponent(cssLevelPlugin)
            .panel
        panel = form
        return panel
    }

    private fun refreshDetectedLabel(src: String, cssLevel: Boolean, at: Long) {
        val whenStr = if (at == 0L) "never" else java.time.Instant.ofEpochMilli(at).toString()
        val levelStr = if (cssLevel) " PostCSS pxtorem ENABLED (CSS files untouched)" else ""
        detectedLabel.text = "Detected config: source=$src$levelStr ; last detected: $whenStr"
    }

    override fun isModified(): Boolean {
        val s = ProjectConfigService.getInstance(project).state
        return s.overridden != overrideBox.isSelected ||
                s.unitToConvert != unitField.text.trim() ||
                s.rootValue != (rootValue.text.toDoubleOrNull() ?: 0.0) ||
                s.unitPrecision != unitPrecision.number.toInt() ||
                s.viewportWidth != (vwWidth.text.toDoubleOrNull() ?: 0.0) ||
                s.viewportHeight != (vwHeight.text.toDoubleOrNull() ?: 0.0) ||
                s.minPixelValue != (minPixelValue.text.toDoubleOrNull() ?: 0.0) ||
                s.propList != propListArea.text ||
                s.cssLevelPluginEnabled != cssLevelPlugin.isSelected
    }

    override fun apply() {
        val svc = ProjectConfigService.getInstance(project)
        val newCfg = Px2RemConfig(
            source = svc.state.detectedSource,
            cssLevelPluginEnabled = cssLevelPlugin.isSelected,
            unitToConvert = unitField.text.trim().ifBlank { "rem" },
            rootValue = (rootValue.text.toDoubleOrNull() ?: 16.0).coerceAtLeast(1.0),
            unitPrecision = unitPrecision.number.toInt(),
            propList = propListArea.text.split(",").map { it.trim() }.filter { it.isNotEmpty() }.ifEmpty { listOf("*") },
            minPixelValue = (minPixelValue.text.toDoubleOrNull() ?: 0.0).coerceAtLeast(0.0),
            viewportWidth = (vwWidth.text.toDoubleOrNull() ?: 750.0).coerceAtLeast(1.0),
            viewportHeight = (vwHeight.text.toDoubleOrNull() ?: 1334.0).coerceAtLeast(1.0),
        )
        if (overrideBox.isSelected) svc.applyOverride(newCfg) else {
            // copy values but reset override; user forced detection to take effect.
            svc.applyOverride(newCfg)
            svc.state.overridden = false
        }
    }

    override fun reset() {
        val s = ProjectConfigService.getInstance(project).state
        overrideBox.isSelected = s.overridden
        unitField.text = s.unitToConvert
        rootValue.text = s.rootValue.toString()
        unitPrecision.number = s.unitPrecision
        vwWidth.text = s.viewportWidth.toString()
        vwHeight.text = s.viewportHeight.toString()
        minPixelValue.text = s.minPixelValue.toString()
        propListArea.text = s.propList
        cssLevelPlugin.isSelected = s.cssLevelPluginEnabled
    }
}
