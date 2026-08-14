package com.github.reactunitconverter.ui

import com.github.reactunitconverter.extract.InlineStyleExtractor
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Font
import javax.swing.*

/**
 * Modal dialog shown after the "Extract inline style to CSS Module" action.
 *
 * - Shows the proposed class name (editable).
 * - Live-updates a preview of the generated CSS rule and the JSX replacement.
 * - User confirms the name to finalize the refactor.
 */
class RenameClassNameDialog(
    private val styleObjectText: String,
    initialName: String,
    private val cssModuleImportName: String = "styles",
    private val existingClassNames: Set<String> = emptySet(),
) : DialogWrapper(true) {

    private val nameField: JBTextField
    private val cssPreviewArea: JBTextArea
    private val jsxPreviewArea: JBTextArea
    private val errorLabel: JBLabel

    /** Finalized class name after user clicks OK. null means cancelled. */
    var chosenName: String? = null; private set
    var extracted: InlineStyleExtractor.ExtractedCss? = null; private set

    init {
        title = "Extract to CSS Module - choose class name"
        setSize(640, 520)

        nameField = JBTextField(initialName).apply {
            font = UIUtil.getLabelFont().deriveFont(Font.BOLD)
            document.addDocumentListener(object : javax.swing.event.DocumentListener {
                override fun insertUpdate(e: javax.swing.event.DocumentEvent?) = refresh()
                override fun removeUpdate(e: javax.swing.event.DocumentEvent?) = refresh()
                override fun changedUpdate(e: javax.swing.event.DocumentEvent?) = refresh()
            })
        }
        cssPreviewArea = JBTextArea().apply {
            font = UIUtil.getLabelFont().deriveFont(Font.PLAIN, 13f)
            isEditable = false
            border = JBUI.Borders.empty(8, 10)
            background = JBColor(0xF7F8FA, 0x1E1F22)
            lineWrap = true
            wrapStyleWord = true
        }
        jsxPreviewArea = JBTextArea().apply {
            font = UIUtil.getLabelFont().deriveFont(Font.PLAIN, 13f)
            isEditable = false
            border = JBUI.Borders.empty(8, 10)
            background = JBColor(0xF7F8FA, 0x1E1F22)
            lineWrap = true
            wrapStyleWord = true
        }
        errorLabel = JBLabel().apply {
            foreground = JBColor.red
        }

        init()
        refresh()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout(0, JBUI.scale(10)))
        panel.border = JBUI.Borders.empty(12)

        val top = JPanel(BorderLayout(0, 6)).apply {
            add(JBLabel("Class name (camelCase, valid CSS Module identifier):").apply {
                labelFor = nameField
            }, BorderLayout.NORTH)
            add(nameField, BorderLayout.CENTER)
            add(errorLabel, BorderLayout.SOUTH)
        }

        val cssPane = JScrollPane(cssPreviewArea).apply {
            preferredSize = Dimension(600, 160)
            border = BorderFactory.createTitledBorder("CSS Module preview (.module.css)")
        }
        val jsxPane = JScrollPane(jsxPreviewArea).apply {
            preferredSize = Dimension(600, 90)
            border = BorderFactory.createTitledBorder("JSX replacement")
        }
        val previews = JPanel(BorderLayout(0, JBUI.scale(8)))
        previews.add(cssPane, BorderLayout.CENTER)
        previews.add(jsxPane, BorderLayout.SOUTH)

        panel.add(top, BorderLayout.NORTH)
        panel.add(previews, BorderLayout.CENTER)
        return panel
    }

    override fun getPreferredFocusedComponent(): JComponent = nameField

    private fun refresh() {
        val name = nameField.text.trim()
        val validation = validateName(name)
        isOKActionEnabled = validation == null
        errorLabel.text = validation.orEmpty()

        val effectiveName = if (validation == null) name else "classNamePreview"
        val e = InlineStyleExtractor.extract(styleObjectText, effectiveName, cssModuleImportName)
        extracted = e

        cssPreviewArea.text = buildString {
            append(".$effectiveName {\n")
            append(e.cssRuleBody)
            if (e.cssRuleBody.isNotBlank()) append('\n')
            append("}")
            if (e.diagnostics.isNotEmpty()) {
                append("\n\n/* notes:\n")
                for (d in e.diagnostics) append("   - ").append(d).append('\n')
                append("*/")
            }
        }
        jsxPreviewArea.text = e.jsxReplacement
    }

    private fun validateName(name: String): String? {
        if (name.isBlank()) return "Class name cannot be empty"
        if (!name.matches(Regex("[A-Za-z_][\\w-]*"))) return "Invalid class name: must start with letter/_ and contain only letters, digits, underscores, hyphens"
        if (name in existingClassNames) return "Class '$name' already exists in this CSS Module"
        return null
    }

    override fun doOKAction() {
        refresh()
        if (!isOKActionEnabled) return
        chosenName = nameField.text.trim()
        super.doOKAction()
    }
}
