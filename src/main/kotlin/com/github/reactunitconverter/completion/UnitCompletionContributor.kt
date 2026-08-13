package com.github.reactunitconverter.completion

import com.github.reactunitconverter.service.ProjectConfigService
import com.intellij.codeInsight.completion.*
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.lang.javascript.psi.JSLiteralExpression
import com.intellij.lang.javascript.psi.JSProperty
import com.intellij.patterns.PlatformPatterns
import com.intellij.util.ProcessingContext

/**
 * Adds completions like "16rem" / "1.6vw" derived from a typed "16px" literal
 * inside React style objects. The px2rem config (rootValue, viewportWidth, etc.)
 * drives the conversion used.
 */
class UnitCompletionContributor : CompletionContributor() {
    init {
        extend(CompletionType.BASIC, PLACE, object : CompletionProvider<CompletionParameters>() {
            override fun addCompletions(parameters: CompletionParameters, context: ProcessingContext, result: CompletionResultSet) {
                val project = parameters.editor.project ?: return
                val pos = parameters.position
                val literal = pos.parent as? JSLiteralExpression ?: return
                val prop = literal.parent as? JSProperty ?: return
                // Only inside style attribute style={{...}}
                if (!insideStyleAttr(prop)) return

                val raw = literal.value as? String ?: literal.text ?: return
                val m = REGEX.matchEntire(raw.trim()) ?: return
                val pxStr = m.groupValues[1]
                val px = pxStr.toDoubleOrNull() ?: return

                val cfg = ProjectConfigService.getInstance(project).currentConfig()
                if (!cfg.isPropAllowed(propCssName(prop))) return

                val rootValue = cfg.rootValue
                val vpWidth = cfg.viewportWidth
                val prec = cfg.unitPrecision
                fun fmt(n: Double): String {
                    val rnd = kotlin.math.round(n * kotlin.math.pow(10.0, prec.toDouble())) /
                            kotlin.math.pow(10.0, prec.toDouble())
                    var s = rnd.toString()
                    if ('.' in s) {
                        while (s.endsWith('0')) s = s.dropLast(1)
                        if (s.endsWith('.')) s = s.dropLast(1)
                    }
                    return s
                }
                val rem = fmt(px / rootValue)
                val vw = fmt(px * 100.0 / vpWidth)
                val toAdd = listOfNotNull(
                    if (cfg.unitToConvert == "rem" || cfg.unitToConvert == "em") "${rem}rem (from ${px}px)" to "\"${rem}rem\"" else null,
                    if (cfg.unitToConvert == "vw") "${vw}vw (from ${px}px)" to "\"${vw}vw\"" else null,
                    // Always offer vw and rem variants as hints
                    if (cfg.unitToConvert != "rem" && cfg.unitToConvert != "em") "${rem}rem" to "\"${rem}rem\"" else null,
                    if (cfg.unitToConvert != "vw") "${vw}vw" to "\"${vw}vw\"" else null,
                ).distinctBy { it.first }

                for ((label, value) in toAdd) {
                    result.addElement(
                        PrioritizedLookupElement.withPriority(
                            LookupElementBuilder.create(value).withPresentableText(label).withTailText("  px2rem"),
                            100.0
                        )
                    )
                }
            }
        })
    }

    private fun insideStyleAttr(prop: JSProperty): Boolean {
        var cur: com.intellij.psi.PsiElement? = prop.parent
        while (cur != null) {
            if (cur is com.intellij.lang.javascript.psi.JSXAttribute) {
                val name = cur.name?.trim()
                if (name.equals("style", ignoreCase = true) || name?.endsWith("Style") == true) return true
            }
            if (cur is com.intellij.lang.javascript.psi.JSXElement || cur is com.intellij.lang.javascript.psi.JSFile) return false
            cur = cur.parent
        }
        return false
    }

    private fun propCssName(prop: JSProperty): String {
        val name = prop.name ?: ""
        return buildString {
            for (ch in name) if (ch.isUpperCase()) { append('-'); append(ch.lowercase()) } else append(ch)
        }
    }

    companion object {
        private val REGEX = Regex("""(-?\d+(?:\.\d+)?)\s*px""", RegexOption.IGNORE_CASE)
        private val PLACE = PlatformPatterns.psiElement()
            .inside(JSLiteralExpression::class.java)
            .withParent(JSProperty::class.java)
    }
}
