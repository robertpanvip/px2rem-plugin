package com.github.reactunitconverter.action

import com.github.reactunitconverter.analyzer.ReactCssPropertyTracker
import com.github.reactunitconverter.converter.InlineStylePxConverter
import com.github.reactunitconverter.extract.StyleObjectExtraction
import com.github.reactunitconverter.runtime.PxToRemHelperService
import com.github.reactunitconverter.service.ProjectConfigService
import com.intellij.codeInsight.intention.impl.BaseIntentionAction
import com.intellij.lang.javascript.psi.*
import com.intellij.lang.javascript.psi.ecma6.TypeScriptVariable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlAttribute
import com.intellij.psi.xml.XmlElement
import com.intellij.util.IncorrectOperationException

/**
 * Converts px values inside React inline styles (the `style={{ ... }}` JSX attribute)
 * to the target unit configured for the project (rem/vw).
 *
 *   - **Static literals** (`"16px"` / `100`) → inlined as `"1rem"` / `"6.25rem"`.
 *   - **Dynamic values** (variable references, function returns, ternary, arithmetic,
 *     logical && / || / ??) → wrapped into `pxToRem(expr)` / `pxToVw(expr, viewportWidth)`
 *     using the helper at `src/utils/rem.ts` (auto-generated + import auto-inserted).
 *   - The PSI tracker [ReactCssPropertyTracker] tries to confirm that a dynamic reference
 *     is actually a CSS-property-bearing value before wrapping; unknown references in a
 *     pixel-style property context are still wrapped conservatively.
 *
 * Only converts properties matching `propList` and respects `minPixelValue`.
 * When project PostCSS pxtorem is detected, CSS files are intentionally left untouched.
 */
class ConvertInlineStyleAction : BaseIntentionAction() {

    override fun getText(): String = "Convert px in inline styles to configured unit"

    override fun getFamilyName(): String = "React Unit Converter"

    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile): Boolean {
        if (!isReactishFile(file)) return false
        return findTargetStyleObjects(file, editor).any()
    }

    @Throws(IncorrectOperationException::class)
    override fun invoke(project: Project, editor: Editor?, file: PsiFile) {
        val config = ProjectConfigService.getInstance(project).currentConfig()
        val converter = InlineStylePxConverter(config)
        val targets = findTargetStyleObjects(file, editor)
        if (targets.isEmpty()) return
        val document = editor?.document ?: return
        val helperService = PxToRemHelperService.getInstance(project)

        // Warn if CSS is also handled by postcss-pxtorem (which is expected, we only process inline)
        if (config.cssLevelPluginEnabled) {
            ApplicationManager.getApplication().invokeLater {
                Messages.showInfoMessage(
                    project,
                    "Project PostCSS pxtorem detected (from ${config.source}).\n" +
                            "CSS files will be left untouched, only React inline styles are being converted.\n" +
                            "Target unit: ${config.unitToConvert}, rootValue: ${config.rootValue}",
                    "React Unit Converter"
                )
            }
        }

        // Collect (pointer, range, converted) for each object.
        val pointers = targets.map {
            SmartPointerManager.getInstance(project).createSmartPsiElementPointer(it) to it.textRange
        }

        var anyDynamic = false
        val hasVw = config.unitToConvert == "vw" || config.unitToConvert == "vh"
        var helperFile: VirtualFile? = null

        WriteCommandAction.writeCommandAction(project, file)
            .withName("Convert px to ${config.unitToConvert} in inline style")
            .run<Throwable> {
        // Collect all replacements up-front using ORIGINAL offsets. Applying them one-by-one
        // while iterating the same PSI elements would corrupt later offsets after the first
        // replaceString (the PSI tree is not re-parsed in between), scrambling multiple
        // style objects (Bug #3).
        data class Edit(val start: Int, val end: Int, val text: String)
        val edits = mutableListOf<Edit>()
        for ((ptr, _) in pointers) {
            val el = ptr.element ?: continue
            val styleSrc = el.text
            val convs = converter.scan(styleSrc, "style")
            if (convs.isEmpty()) continue
            if (convs.any { it.isDynamic }) anyDynamic = true
            val newSrc = converter.apply(styleSrc, convs)
            val startOff = el.textRange.startOffset
            val endOff = startOff + styleSrc.length
            if (endOff > document.textLength) continue
            edits += Edit(startOff, endOff, newSrc)
        }
        // Apply in reverse document order (later text first) so earlier offsets stay valid.
        edits.sortedByDescending { it.start }.forEach { e ->
            document.replaceString(e.start, e.end, e.text)
        }
                if (anyDynamic) {
                    helperFile = helperService.findOrCreateHelper()
                    val h = helperFile
                    if (h != null) {
                        helperService.ensureImported(file, h, needVw = hasVw)
                    }
                }
                PsiManager.getInstance(project).reloadFromDisk(file)
                val ranges: Collection<TextRange> = pointers.mapNotNull { p ->
                    val e = p.first.element ?: return@mapNotNull null
                    TextRange(e.textRange.startOffset, e.textRange.endOffset)
                }
                if (ranges.isNotEmpty()) {
                    CodeStyleManager.getInstance(project).reformatText(file, ranges)
                }
            }
    }

    companion object {
        fun isReactishFile(file: PsiFile): Boolean = file is JSFile && (file.name.endsWith(".tsx") || file.name.endsWith(".jsx") || file.name.endsWith(".ts") || file.name.endsWith(".js"))

        /**
         * Find JS style object literals the cursor is inside, OR all such objects in the file
         * if the cursor isn't positioned in one.
         */
        fun findTargetStyleObjects(file: PsiFile, editor: Editor?): List<JSObjectLiteralExpression> {
            val caret = editor?.caretModel?.offset
            if (caret != null) {
                val leaf = file.findElementAt(caret) ?: file.findElementAt((caret - 1).coerceAtLeast(0))
                if (leaf != null) {
                    val inStyle = findEnclosingStyleObject(leaf)
                    if (inStyle != null) return listOf(inStyle)
                }
            }
            val out = mutableListOf<JSObjectLiteralExpression>()
            // 1) inline object literals: style={{ ... }} (existing behavior)
            file.accept(object : JSRecursiveElementVisitor() {
                override fun visitJSObjectLiteralExpression(node: JSObjectLiteralExpression) {
                    if (isInsideStyleAttr(node)) out += node
                    super.visitJSObjectLiteralExpression(node)
                }
            })
            // 2) style={styles} variable references → resolve the reference and convert the
            //    declaration's object-literal initializer in place (works for
            //    `const styles: React.CSSProperties = {...}` / `const styles = {...}` /
            //    `const styles = {...} as React.CSSProperties`).
            for (attr in PsiTreeUtil.collectElementsOfType(file, XmlAttribute::class.java)) {
                if (!isStyleAttrName(attr)) continue
                val value = attr.valueElement ?: continue
                // inline object literals were already collected above
                if (PsiTreeUtil.findChildOfType(value, JSObjectLiteralExpression::class.java) != null) continue
                val ref = PsiTreeUtil.findChildOfType(value, JSReferenceExpression::class.java) ?: continue
                val variable = resolveStyleVariable(ref) ?: continue
                // keep the pure text check as the tested source of truth before trusting the PSI initializer
                if (StyleObjectExtraction.objectLiteralSource(variable.text) == null) continue
                val initializer = initializerOf(variable) ?: continue
                if (initializer !in out) out += initializer
            }
            return out
        }

        /** True when [ref] resolves to a variable declaration (`const/let/var styles = ...`). */
        private fun resolveStyleVariable(ref: JSReferenceExpression): PsiElement? {
            val target = ref.resolve() ?: return null
            return when (target) {
                is JSVariable, is TypeScriptVariable -> target
                else -> null
            }
        }

        /** The object-literal initializer of a style variable, or null when it isn't an object literal. */
        private fun initializerOf(variable: PsiElement): JSObjectLiteralExpression? =
            when (variable) {
                is JSVariable -> variable.initializer as? JSObjectLiteralExpression
                is TypeScriptVariable -> variable.initializer as? JSObjectLiteralExpression
                else -> null
            }

        private fun isStyleAttrName(attr: XmlAttribute): Boolean {
            val name = attr.name?.trim() ?: return false
            return name.equals("style", ignoreCase = true) || name.endsWith("Style")
        }

        private fun findEnclosingStyleObject(leaf: PsiElement): JSObjectLiteralExpression? {
            var cur: PsiElement? = leaf
            while (cur != null) {
                if (cur is JSObjectLiteralExpression && isInsideStyleAttr(cur)) return cur
                if (cur is XmlElement || cur is JSFile) return null
                cur = cur.parent
            }
            return null
        }

        private fun isInsideStyleAttr(obj: JSObjectLiteralExpression): Boolean {
            val attr = PsiTreeUtil.getParentOfType(obj, XmlAttribute::class.java) ?: return false
            return isStyleAttrName(attr)
        }

        /** True when a JSProperty's name matches a React style-like property (camelCase layout key). */
        fun isCssPropertyLike(prop: JSProperty): Boolean {
            val name = prop.name ?: return false
            val knownLayout = setOf("margin", "padding", "border", "width", "height", "display", "position",
                "top", "left", "right", "bottom", "color", "background", "font", "flex", "grid", "gap",
                "radius", "shadow", "opacity", "zIndex")
            val lower = name.replace("-", "").lowercase()
            return knownLayout.any { lower.contains(it) }
        }

        @JvmStatic
        fun extractStyleObjectText(attribute: XmlAttribute): Pair<String, IntRange>? {
            // style={{ ... }}  => the inner JSObjectLiteralExpression (second level)
            val value = attribute.valueElement as? com.intellij.psi.PsiElement ?: return null
            val inner = PsiTreeUtil.findChildOfType(value, JSObjectLiteralExpression::class.java)
                ?: (value as? JSObjectLiteralExpression)
                ?: return null
            return inner.text to inner.textRange.startOffset.let { s -> s until s + inner.text.length }
        }
    }
}
