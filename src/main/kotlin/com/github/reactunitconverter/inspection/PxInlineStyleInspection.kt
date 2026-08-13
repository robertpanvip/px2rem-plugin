package com.github.reactunitconverter.inspection

import com.github.reactunitconverter.action.ConvertInlineStyleAction
import com.github.reactunitconverter.converter.InlineStylePxConverter
import com.github.reactunitconverter.service.ProjectConfigService
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.lang.javascript.psi.JSObjectLiteralExpression
import com.intellij.lang.javascript.psi.JSRecursiveElementVisitor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.xml.XmlAttribute
import com.intellij.psi.xml.XmlElement

/**
 * Weak warning that flags React inline style objects containing px values that
 * would be candidates for conversion by [ConvertInlineStyleAction].
 *
 * Quickfix: apply the conversion right from the inspection.
 */
class PxInlineStyleInspection : LocalInspectionTool() {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        val project = holder.project
        val cfg = ProjectConfigService.getInstance(project).currentConfig()
        val converter = InlineStylePxConverter(cfg)
        return object : JSRecursiveElementVisitor() {
            override fun visitJSObjectLiteralExpression(node: JSObjectLiteralExpression) {
                super.visitJSObjectLiteralExpression(node)
                if (!isInsideStyleAttr(node)) return
                val text = node.text
                val candidates = converter.scan(text, "style")
                if (candidates.isEmpty()) return
                val sample = candidates.first()
                val markRange = if (sample.range.first < text.length) {
                    val relStart = sample.range.first
                    val relEnd = (sample.range.last + 1).coerceAtMost(text.length)
                    node.textRange.startOffset + relStart to node.textRange.startOffset + relEnd
                } else node.textRange.startOffset to node.textRange.endOffset
                // use the start offset to pick a leaf element inside the node
                val vFile = node.containingFile.virtualFile
                val document = if (vFile != null) FileDocumentManager.getInstance().getDocument(vFile) else null
                val startOffset = markRange.first
                val endOffset = markRange.second
                val leaf = node.containingFile.findElementAt(startOffset) ?: node
                val msg = "Inline style has '${sample.original}' which can convert to ${sample.converted} (${cfg.unitToConvert}; source=${cfg.source})"
                // register via holder's range-aware method on the leaf element with relative range
                val relativeStart = (startOffset - leaf.textRange.startOffset).coerceAtLeast(0)
                val relativeEnd = (endOffset - leaf.textRange.startOffset).coerceAtMost(leaf.textRange.length)
                if (relativeEnd > relativeStart) {
                    val rangeInLeaf = TextRange(relativeStart, relativeEnd)
                    holder.registerProblem(
                        leaf,
                        msg,
                        ProblemHighlightType.WEAK_WARNING,
                        rangeInLeaf,
                        QuickFix(node.text)
                    )
                } else {
                    holder.registerProblem(
                        node,
                        msg,
                        ProblemHighlightType.WEAK_WARNING,
                        null as TextRange?,
                        QuickFix(node.text)
                    )
                }
            }
        }
    }

    private fun isInsideStyleAttr(obj: JSObjectLiteralExpression): Boolean {
        var cur: PsiElement? = obj.parent
        while (cur != null) {
            if (cur is XmlAttribute) {
                val name = cur.name?.trim()
                if (name.equals("style", ignoreCase = true) || name?.endsWith("Style") == true) return true
            }
            if (cur is XmlElement || cur is com.intellij.lang.javascript.psi.JSFile) return false
            cur = cur.parent
        }
        return false
    }

    private class QuickFix(private val styleObjectText: String) : com.intellij.codeInspection.LocalQuickFix {
        override fun getName(): String = "Convert px to configured unit in this style"
        override fun getFamilyName(): String = "React Unit Converter"
        override fun applyFix(project: com.intellij.openapi.project.Project, descriptor: com.intellij.codeInspection.ProblemDescriptor) {
            // Delegate: open an editor instance and run the action via PSI/file resolution.
            val file = descriptor.psiElement.containingFile
            val editor = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).selectedTextEditor ?: return
            ConvertInlineStyleAction().apply {
                if (isAvailable(project, editor, file)) invoke(project, editor, file)
            }
        }
    }
}
