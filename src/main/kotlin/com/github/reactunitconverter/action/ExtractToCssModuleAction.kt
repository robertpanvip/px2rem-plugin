package com.github.reactunitconverter.action

import com.github.reactunitconverter.extract.ClassNameInferencer
import com.github.reactunitconverter.extract.InlineStyleExtractor
import com.github.reactunitconverter.service.AppSettingsService
import com.github.reactunitconverter.ui.RenameClassNameDialog
import com.intellij.codeInsight.intention.impl.BaseIntentionAction
import com.intellij.lang.javascript.psi.JSFile
import com.intellij.lang.javascript.psi.JSLiteralExpression
import com.intellij.lang.javascript.psi.JSObjectLiteralExpression
import com.intellij.lang.javascript.psi.JSProperty
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlAttribute
import com.intellij.psi.xml.XmlElement
import com.intellij.psi.xml.XmlTag
import com.intellij.util.IncorrectOperationException
import java.io.File

/**
 * Extracts the inline style object at the caret (`style={{ ... }}`) into a CSS Module.
 *
 * Steps:
 * 1. Find/choose the target `.module.css` / `.module.less` / `.module.scss` file.
 *    - If a sibling `<SameName>.module.css` exists, use it.
 *    - Else offer to create one next to the TSX file.
 * 2. Infer a class name from the tag, existing className, parent/sibling class names,
 *    and style properties.
 * 3. Show a rename dialog allowing user to rename, with live CSS + JSX preview.
 * 4. Write the CSS rule into the module file (open editor), replace style={{...}}
 *    with style={styles.className}, add `import styles from '*.module.css'` if missing.
 */
class ExtractToCssModuleAction : BaseIntentionAction() {

    override fun getText(): String = "Extract inline style to CSS Module..."

    override fun getFamilyName(): String = "React Unit Converter"

    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile): Boolean {
        if (!ConvertInlineStyleAction.isReactishFile(file)) return false
        val caret = editor?.caretModel?.offset ?: return false
        val leaf = (file.findElementAt(caret) ?: file.findElementAt((caret - 1).coerceAtLeast(0))) ?: return false
        return findEnclosingStyleAttr(leaf) != null
    }

    @Throws(IncorrectOperationException::class)
    override fun invoke(project: Project, editor: Editor, file: PsiFile) {
        val caret = editor.caretModel.offset
        val leaf = (file.findElementAt(caret) ?: file.findElementAt((caret - 1).coerceAtLeast(0)))
            ?: run { Messages.showErrorDialog(project, "Caret not in file.", "Extract to CSS Module"); return }
        val attr = findEnclosingStyleAttr(leaf) ?: run {
            Messages.showErrorDialog(project, "Please position the caret inside a `style={{...}}` attribute.", "Extract to CSS Module")
            return
        }
        val tag = parentOfType<XmlTag>(attr)
        val (styleText, _) = ConvertInlineStyleAction.extractStyleObjectText(attr) ?: run {
            Messages.showErrorDialog(project, "Cannot parse inline style object.", "Extract to CSS Module")
            return
        }

        val context = ClassNameInferencer.Context(
            jsxTag = tag?.name ?: "div",
            currentClassName = findLiteralAttr(tag, "className"),
            parentClassName = findParentClassName(tag),
            siblingClassNames = findSiblingClassNames(tag),
            ariaLabel = findLiteralAttr(tag, "aria-label"),
            id = findLiteralAttr(tag, "id"),
            dataTestid = findLiteralAttr(tag, "data-testid"),
            role = findLiteralAttr(tag, "role"),
            styleProps = extractPropsPreview(styleText),
            existingClassNames = emptySet()
        )
        val initial = ClassNameInferencer.suggest(context)

        val (moduleFile, createdNew) = resolveOrCreateModuleFile(project, file)
        if (moduleFile == null) return

        val existingNames = readExistingClassNamesInModule(project, moduleFile)

        val dialog = RenameClassNameDialog(
            styleObjectText = styleText,
            initialName = initial,
            cssModuleImportName = AppSettingsService.getInstance().state.cssModuleImportName,
            existingClassNames = existingNames
        )
        if (!dialog.showAndGet()) return
        val chosen = dialog.chosenName ?: return
        val extracted = dialog.extracted ?: return

        val importName = AppSettingsService.getInstance().state.cssModuleImportName

        WriteCommandAction.writeCommandAction(project).withName("Extract inline style to CSS Module").run<Throwable> {
            // 1) Append rule to CSS module file
            val ruleText = buildCssRule(chosen, extracted.cssRuleBody)
            appendOrInsertCssRule(project, moduleFile, ruleText, chosen)

            // 2) Rewrite `style={{...}}` attribute with `style={styles.xxx}`.
            val document = editor.document
            val attrRange = attr.textRange
            val replacement = extracted.jsxReplacement
            document.replaceString(attrRange.startOffset, attrRange.endOffset, replacement)

            // 3) Ensure import statement exists
            ensureCssModuleImport(file, moduleFile, document, project, importName)

            // 4) Format
            PsiManager.getInstance(project).reloadFromDisk(file)
            CodeStyleManager.getInstance(project).reformatText(file, listOf(attrRange))
        }

        // Open CSS module editor so user can verify
        ApplicationManager.getApplication().invokeLater {
            val desc = OpenFileDescriptor(project, moduleFile)
            FileEditorManager.getInstance(project).openTextEditor(desc, true)
        }
        if (createdNew) {
            Messages.showInfoMessage(project, "Created new CSS module: ${moduleFile.name}\nAppended class .$chosen.", "Extract to CSS Module")
        }
    }

    // ---- helpers ----

    private fun <T : PsiElement> parentOfType(e: PsiElement?, cls: Class<T>): T? {
        var cur: PsiElement? = e
        while (cur != null) {
            if (cls.isInstance(cur)) return cls.cast(cur)
            cur = cur.parent
        }
        return null
    }

    private inline fun <reified T : PsiElement> parentOfType(e: PsiElement?): T? =
        parentOfType(e, T::class.java)

    private fun findEnclosingStyleAttr(leaf: PsiElement): XmlAttribute? {
        var cur: PsiElement? = leaf
        while (cur != null) {
            if (cur is XmlAttribute) {
                val name = cur.name?.trim()
                if (name.equals("style", ignoreCase = true) || name?.endsWith("Style") == true) return cur
            }
            if (cur is XmlElement || cur is JSFile) return null
            cur = cur.parent
        }
        return null
    }

    private fun findLiteralAttr(tag: XmlTag?, attrName: String): String? {
        if (tag == null) return null
        val attr = tag.getAttribute(attrName) ?: return null
        val valueEl = attr.valueElement ?: return null
        val strValue = attr.value
        if (strValue != null && strValue.isNotEmpty()) return strValue
        // fallback: look inside the value element for a JS literal
        val lit = PsiTreeUtil.findChildOfType(valueEl, JSLiteralExpression::class.java)
        val v = lit?.value
        if (v is String) return v
        return null
    }

    private fun findParentClassName(tag: XmlTag?): String? {
        val parentTag = parentOfType<XmlTag>(tag?.parent) ?: return null
        return findLiteralAttr(parentTag, "className")
    }

    private fun findSiblingClassNames(tag: XmlTag?): List<String> {
        if (tag == null) return emptyList()
        val parent = tag.parent ?: return emptyList()
        return parent.children.mapNotNull { child ->
            if (child is XmlTag && child !== tag) findLiteralAttr(child, "className") else null
        }
    }

    private fun extractPropsPreview(styleText: String): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        val clean = if (styleText.trim().startsWith("{")) styleText.trim().drop(1).dropLast(1) else styleText
        val rx = Regex("""['"]?([A-Za-z_][\w-]*)['"]?\s*:\s*([^,{}]+)""")
        for (m in rx.findAll(clean)) {
            val k = m.groupValues[1]
            val v = m.groupValues[2].trim()
            out[k] = when {
                (v.startsWith('"') && v.endsWith('"')) || (v.startsWith('\'') && v.endsWith('\'')) -> v.substring(1, v.length - 1)
                v.toDoubleOrNull() != null -> v.toDouble()
                else -> v
            }
        }
        return out
    }

    private fun resolveOrCreateModuleFile(project: Project, psiFile: PsiFile): Pair<VirtualFile?, Boolean> {
        val vFile = psiFile.virtualFile ?: return null to false
        val dir = vFile.parent ?: return null to false
        val baseName = vFile.nameWithoutExtension
        for (candidate in listOf("$baseName.module.css", "$baseName.module.less", "$baseName.module.scss", "$baseName.module.sass")) {
            val f = dir.findChild(candidate)
            if (f != null && f.exists()) return f to false
        }
        val choice = Messages.showYesNoCancelDialog(
            project,
            "No CSS Module file found next to ${vFile.name}.\nCreate ${baseName}.module.css ?",
            "Create CSS Module",
            "Create", "Choose existing...", "Cancel",
            Messages.getQuestionIcon()
        )
        if (choice == Messages.CANCEL) return null to false
        if (choice == Messages.NO) {
            // pick an existing .module.css file from project
            val jfc = com.intellij.openapi.fileChooser.FileChooser.chooseFile(
                com.intellij.openapi.fileChooser.FileChooserDescriptor(true, false, false, false, false, false)
                    .withFileFilter { it.name.endsWith(".module.css") || it.name.endsWith(".module.less") || it.name.endsWith(".module.scss") },
                project, null
            )
            return (jfc ?: return null to false) to false
        }
        // create
        val ioFile = File(dir.path, "$baseName.module.css")
        if (!ioFile.exists()) runCatching { ioFile.createNewFile() }
        LocalFileSystem.getInstance().refresh(false)
        val created = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(ioFile) ?: return null to false
        return created to true
    }

    private fun readExistingClassNamesInModule(project: Project, vFile: VirtualFile): Set<String> {
        val psi = PsiManager.getInstance(project).findFile(vFile) ?: return emptySet()
        val text = psi.text
        return Regex("""\.([A-Za-z_][\w-]*)\s*\{""")
            .findAll(text)
            .map { it.groupValues[1] }
            .toSet()
    }

    private fun buildCssRule(className: String, body: String): String {
        val body2 = if (body.isBlank()) "" else "\n$body\n"
        return ".$className {$body2}\n"
    }

    private fun appendOrInsertCssRule(project: Project, vFile: VirtualFile, rule: String, className: String) {
        val psi = PsiManager.getInstance(project).findFile(vFile) ?: return
        val text = psi.text
        val existing = Regex("""\.${Regex.escape(className)}\s*\{""").containsMatchIn(text)
        val newText = if (existing) {
            // Replace: find class rule range, replace block
            val m = Regex("""\.${Regex.escape(className)}\s*\{[^{}]*\}""").find(text) ?: (text + rule)
            if (m is MatchResult) text.replace(m.value, rule.trim()) else text + "\n" + rule
        } else {
            val sep = if (text.isNotBlank() && !text.endsWith("\n")) "\n\n" else "\n"
            text + sep + rule
        }
        val doc = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(vFile) ?: return
        WriteCommandAction.runWriteCommandAction(project) {
            doc.setText(newText)
        }
        CodeStyleManager.getInstance(project).reformat(psi)
        VfsUtil.markDirtyAndRefresh(false, false, false, vFile)
    }

    private fun ensureCssModuleImport(psiFile: PsiFile, moduleFile: VirtualFile, doc: com.intellij.openapi.editor.Document, project: Project, importName: String) {
        val file = psiFile.virtualFile ?: return
        val rel = VfsUtil.findRelativePath(file.parent, moduleFile, '/') ?: moduleFile.path
        val expected = """import $importName from "./$rel";"""
        if (doc.text.contains(expected) || doc.text.contains("""import $importName from '$rel';""")) return
        // find last import statement
        val importLines = Regex("""^\s*import\s+[^;]+;\s*$""", RegexOption.MULTILINE).findAll(doc.text).toList()
        val insertOff = if (importLines.isEmpty()) 0 else importLines.last().range.endInclusive + 1
        val prefix = if (insertOff == 0 && doc.text.isNotBlank()) "" else ""
        val suffix = if (insertOff != 0) "\n" else ""
        doc.insertString(insertOff, "$prefix$suffix$expected\n")
    }
}
