package com.github.reactunitconverter.analyzer

import com.intellij.lang.javascript.psi.*
import com.intellij.lang.javascript.psi.ecma6.TypeScriptVariable
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlAttribute

/**
 * Tries to answer: "Is this expression that I'm about to wrap with pxToRem(...) actually
 *  coming from a variable / function whose declared type / assigned value is a
 *  React.CSSProperties style object (or a px number)?"
 *
 * Specifically, user requirement:
 *   "inlineStyle 变量引用、函数返回、等变量追踪如果是 React.CSSProperty 都需要转化"
 *
 * Detection strategy (PSI-level, falls back to conservative heuristics when type info is
 * missing):
 *   - JSReferenceExpression → resolve → if target is
 *        a) `const/let/var x = { width: 100, ... }` => style-like object → treat as React.CSSProperties
 *        b) `const x = 16` (number, in layout-style context) → px number
 *        c) type annotation `x: React.CSSProperties` / `x: CSSProperties` / `x: React.CSSProperties["width"]`
 *        d) function `function f(): React.CSSProperties {}` / `const f = (): CSSProperties => ({...})`
 *        e) conditional/ternary branches that themselves resolve to style props → recursively check
 *   - Call expressions: follow return type via TS type or inferred body object.
 *   - Binary arithmetic (`a + b`, `n * 2`) on numeric-looking operands → treat as px.
 *   - Spreads: skip (we don't wrap `...x` style props via this tracker — they're separate).
 *
 * For the purposes of this plugin we never unwrap whole style objects here — that's handled
 * elsewhere (the Action finds the `style={{...}}` object literal directly). Instead this
 * tracker is used per-**value** inside that object, deciding whether a dynamic expression
 * "looks enough like a px-bearing React.CSSProperties value" to wrap.
 */
object ReactCssPropertyTracker {

    data class Verdict(
        val kind: Kind,
        val reason: String,
        val referencedDefinition: PsiElement? = null,
    ) {
        enum class Kind {
            /** This expression *is* a React.CSSProperties **value** (width/margin/etc px-bearing). Wrap it. */
            CSS_PROPERTY_VALUE,
            /** This expression evaluates to a full React.CSSProperties **object**. Don't wrap the whole object. */
            CSS_PROPERTIES_OBJECT,
            /** Looks like a plain px number from a variable / arithmetic. Wrap it. */
            PIXEL_NUMBER,
            /** Unknown / non-style value. Leave it alone. */
            UNKNOWN,
        }

        fun shouldWrapAsValue(): Boolean = kind == Kind.CSS_PROPERTY_VALUE || kind == Kind.PIXEL_NUMBER
    }

    fun analyzeExpression(expr: JSExpression?, contextProp: String?): Verdict {
        if (expr == null) return Verdict(Verdict.Kind.UNKNOWN, "null expr")
        return when (expr) {
            is JSLiteralExpression -> analyzeLiteral(expr)
            is JSReferenceExpression -> analyzeReference(expr, contextProp)
            is JSCallExpression -> analyzeCall(expr, contextProp)
            is JSBinaryExpression -> analyzeBinary(expr, contextProp)
            is JSConditionalExpression -> analyzeTernary(expr, contextProp)
            is JSPostfixExpression, is JSPrefixExpression ->
                Verdict(Verdict.Kind.PIXEL_NUMBER, "unary numeric operator suggests pixel length", expr)
            is JSObjectLiteralExpression ->
                Verdict(Verdict.Kind.CSS_PROPERTIES_OBJECT, "nested object literal", expr)
            is JSAssignmentExpression ->
                analyzeExpression(expr.rOperand, contextProp).let { it.copy(reason = "assignment rhs: ${it.reason}") }
            is JSParenthesizedExpression ->
                analyzeExpression(expr.innerExpression, contextProp)
            else -> Verdict(Verdict.Kind.UNKNOWN, "unsupported expression ${expr.node.elementType}", expr)
        }
    }

    // ---- Literal ----
    private fun analyzeLiteral(expr: JSLiteralExpression): Verdict {
        val v = expr.value
        return when {
            v is Number -> Verdict(Verdict.Kind.PIXEL_NUMBER, "number literal")
            v is String -> if (v.matches(Regex("""-?\d+(?:\.\d+)?\s*px""", RegexOption.IGNORE_CASE)))
                Verdict(Verdict.Kind.CSS_PROPERTY_VALUE, "px string literal")
            else Verdict(Verdict.Kind.UNKNOWN, "string literal non-px")
            else -> Verdict(Verdict.Kind.UNKNOWN, "non-numeric literal")
        }
    }

    // ---- Reference ----
    private fun analyzeReference(ref: JSReferenceExpression, contextProp: String?): Verdict {
        val target = ref.resolve()
        if (target == null) {
            // Unknown reference but the context prop is pixel-bearing → assume it's px
            return if (contextProp != null && !isNonPixelPropName(contextProp))
                Verdict(Verdict.Kind.PIXEL_NUMBER, "unresolved reference in pixel-style prop context", ref)
            else Verdict(Verdict.Kind.UNKNOWN, "unresolved reference", ref)
        }
        return when (target) {
            is JSVariable, is TypeScriptVariable -> analyzeVariable(target, contextProp)
            is JSFunction -> analyzeFunction(target, contextProp)
            is JSParameter -> analyzeParameter(target, contextProp)
            is JSProperty -> {
                // const obj = { width: 100 } -> obj.width refers here; parent is object literal.
                val parent = target.parent
                if (parent is JSObjectLiteralExpression && isStyleLikeObject(parent))
                    Verdict(Verdict.Kind.CSS_PROPERTY_VALUE, "property inside style-like object literal", target)
                else Verdict(Verdict.Kind.UNKNOWN, "property not inside style object", target)
            }
            else -> Verdict(Verdict.Kind.UNKNOWN, "reference target: ${target.javaClass.simpleName}", target)
        }
    }

    private fun analyzeVariable(variable: PsiElement, contextProp: String?): Verdict {
        // Type annotation first
        val typeText = when (variable) {
            is JSVariable -> variable.typeElement?.text?.replace(" ", "")
            is TypeScriptVariable -> variable.typeElement?.text?.replace(" ", "")
            else -> null
        }
        if (typeText != null) {
            if (looksLikeReactCssProperties(typeText)) {
                return Verdict(Verdict.Kind.CSS_PROPERTIES_OBJECT, "type: $typeText", variable)
            }
            if (looksLikeCssPropertyValueType(typeText)) {
                return Verdict(Verdict.Kind.CSS_PROPERTY_VALUE, "type: $typeText", variable)
            }
            if (typeText == "number" || typeText.endsWith("|number")) {
                if (contextProp != null && !isNonPixelPropName(contextProp))
                    return Verdict(Verdict.Kind.PIXEL_NUMBER, "typed number in pixel-style context", variable)
            }
            if (typeText == "string" && contextProp != null && !isNonPixelPropName(contextProp)) {
                return Verdict(Verdict.Kind.CSS_PROPERTY_VALUE, "typed string in style-value context", variable)
            }
        }
        // Infer from initializer
        val initializer = when (variable) {
            is JSVariable -> variable.initializer
            is TypeScriptVariable -> variable.initializer
            else -> null
        } ?: return Verdict(Verdict.Kind.UNKNOWN, "variable without type/initializer", variable)

        return when (initializer) {
            is JSObjectLiteralExpression -> {
                if (isStyleLikeObject(initializer))
                    Verdict(Verdict.Kind.CSS_PROPERTIES_OBJECT, "inferred from style-like initializer", variable)
                else
                    Verdict(Verdict.Kind.UNKNOWN, "object initializer but not style-like", variable)
            }
            is JSLiteralExpression -> analyzeLiteral(initializer).copy(referencedDefinition = variable)
            else -> analyzeExpression(initializer as? JSExpression, contextProp).copy(referencedDefinition = variable)
        }
    }

    private fun analyzeParameter(param: JSParameter, contextProp: String?): Verdict {
        val typeText = param.typeElement?.text?.replace(" ", "")
        return when {
            typeText == null -> Verdict(Verdict.Kind.UNKNOWN, "parameter with no type annotation", param)
            looksLikeReactCssProperties(typeText) -> Verdict(Verdict.Kind.CSS_PROPERTIES_OBJECT, "param type $typeText", param)
            looksLikeCssPropertyValueType(typeText) -> Verdict(Verdict.Kind.CSS_PROPERTY_VALUE, "param type $typeText", param)
            typeText == "number" && contextProp != null && !isNonPixelPropName(contextProp) ->
                Verdict(Verdict.Kind.PIXEL_NUMBER, "number param in pixel-style context", param)
            else -> Verdict(Verdict.Kind.UNKNOWN, "param type: $typeText", param)
        }
    }

    // ---- Call ----
    private fun analyzeCall(call: JSCallExpression, contextProp: String?): Verdict {
        val methodExpr = call.methodExpression
        val resolved = (methodExpr as? com.intellij.psi.PsiReference)?.resolve()
            ?: (methodExpr as? JSReferenceExpression)?.let { ref ->
                (ref as? com.intellij.psi.PsiPolyVariantReference)?.multiResolve(false)?.firstOrNull()?.element
            }
        if (resolved is JSFunction) return analyzeFunction(resolved, contextProp, callSite = call)
        // Without resolved target we still trust prop context
        return if (contextProp != null && !isNonPixelPropName(contextProp))
            Verdict(Verdict.Kind.PIXEL_NUMBER, "function call in pixel-style prop context; no resolve info", call)
        else
            Verdict(Verdict.Kind.UNKNOWN, "unresolved call", call)
    }

    private fun analyzeFunction(fn: JSFunction, contextProp: String?, callSite: JSCallExpression? = null): Verdict {
        val retType = fn.returnTypeElement?.text?.replace(" ", "")
        if (retType != null) {
            if (looksLikeReactCssProperties(retType))
                return Verdict(Verdict.Kind.CSS_PROPERTIES_OBJECT, "return type: $retType", fn)
            if (looksLikeCssPropertyValueType(retType))
                return Verdict(Verdict.Kind.CSS_PROPERTY_VALUE, "return type: $retType", fn)
            if (retType == "number" && contextProp != null && !isNonPixelPropName(contextProp))
                return Verdict(Verdict.Kind.PIXEL_NUMBER, "number return in pixel-style context", fn)
        }
        // Look at body: either `{ return X }` OR arrow `=> X`
        val block = fn.block
        val returned: JSExpression? = when {
            block != null -> {
                val lastReturn = block.statements.lastOrNull { it is JSReturnStatement } as? JSReturnStatement
                lastReturn?.expression
            }
            // shorthand arrow: (x) => expr has no block, treat function's first child expression as body
            else -> fn.children.filterIsInstance<JSExpression>().firstOrNull()
        }
        if (returned == null) return Verdict(Verdict.Kind.UNKNOWN, "function with no body/return", fn)
        return analyzeExpression(returned, contextProp).copy(referencedDefinition = fn)
    }

    // ---- Binary ----
    private fun analyzeBinary(expr: JSBinaryExpression, contextProp: String?): Verdict {
        val op = expr.operationNode?.text?.trim() ?: "?"
        val left = expr.lOperand
        val right = expr.rOperand
        return when (op) {
            "+", "-", "*", "/" -> {
                // Arithmetic → pixel number (if prop context allows)
                if (contextProp != null && !isNonPixelPropName(contextProp))
                    Verdict(Verdict.Kind.PIXEL_NUMBER, "arithmetic $op in pixel-style context", expr)
                else
                    Verdict(Verdict.Kind.UNKNOWN, "arithmetic $op outside style prop context", expr)
            }
            "&&", "||", "??" -> {
                // `a && value` or `a || fallback`. RHS is what matters most.
                val rightV = analyzeExpression(right, contextProp)
                if (rightV.shouldWrapAsValue() || rightV.kind == Verdict.Kind.CSS_PROPERTIES_OBJECT) return rightV
                val leftV = analyzeExpression(left, contextProp)
                if (leftV.shouldWrapAsValue() || leftV.kind == Verdict.Kind.CSS_PROPERTIES_OBJECT) return leftV
                Verdict(Verdict.Kind.UNKNOWN, "logical $op with non-style branches", expr)
            }
            else -> Verdict(Verdict.Kind.UNKNOWN, "binary op $op", expr)
        }
    }

    // ---- Ternary ----
    private fun analyzeTernary(expr: JSConditionalExpression, contextProp: String?): Verdict {
        val thenBranch = expr.thenBranch
        val elseBranch = expr.elseBranch
        val thenV = analyzeExpression(thenBranch, contextProp)
        val elseV = analyzeExpression(elseBranch, contextProp)
        // If both agree, take it; if either is style-ish, assume value (conservative).
        if (thenV.kind == Verdict.Kind.CSS_PROPERTIES_OBJECT && elseV.kind == Verdict.Kind.CSS_PROPERTIES_OBJECT)
            return Verdict(Verdict.Kind.CSS_PROPERTIES_OBJECT, "ternary → both branches are CSSProperties", expr)
        if (thenV.shouldWrapAsValue() || elseV.shouldWrapAsValue())
            return Verdict(Verdict.Kind.CSS_PROPERTY_VALUE, "ternary → at least one branch is CSS value", expr)
        return Verdict(Verdict.Kind.UNKNOWN, "ternary non-style branches", expr)
    }

    // ---- Heuristics ----

    private val STYLE_KEY_HINTS = setOf(
        "width", "height", "top", "left", "right", "bottom",
        "margin", "padding", "border", "gap", "inset",
        "font", "fontsize", "textindent", "letterspacing", "lineheight",
        "backgroundposition", "backgroundsize", "backgroundpositionx", "backgroundpositiony",
        "borderradius", "borderwidth", "borderleft", "borderright", "bordertop", "borderbottom",
        "marginleft", "marginright", "margintop", "marginbottom",
        "paddingleft", "paddingright", "paddingtop", "paddingbottom",
        "flexbasis", "gridrowgap", "gridcolumngap", "columnwidth", "columnrulewidth",
        "minwidth", "maxwidth", "minheight", "maxheight",
        "boxshadow", "textshadow", "strokewidth", "outlinewidth", "outlineoffset",
        "transformorigin", "translate", "perspectiveorigin",
        "rowgap", "columngap", "gridtemplatecolumns", "gridtemplaterows",
    )

    fun isStyleLikeObject(obj: JSObjectLiteralExpression): Boolean {
        val props = obj.properties
        if (props.isEmpty()) return false
        var hint = 0
        for (p in props) {
            val name = (p.name ?: continue).lowercase().replace("-", "")
            if (STYLE_KEY_HINTS.contains(name)) hint++
            if (name.startsWith("margin") || name.startsWith("padding") ||
                name.startsWith("border") || name.startsWith("background") ||
                name == "fontsize" || name == "font-size") hint++
        }
        return hint >= 1
    }

    /**
     * Delegates to [ReactCssPropertyShape] so the PSI tracker and the string-level
     * heuristics can never drift again (Bug #2: indexed types such as
     * `CSSProperties["width"]` are a *value* type, NOT a whole `CSSProperties` object —
     * the shape adds a `t.contains("[")` guard that the old duplicated logic missed).
     */
    fun looksLikeReactCssProperties(typeText: String): Boolean =
        ReactCssPropertyShape.looksLikeReactCssProperties(typeText)

    fun looksLikeCssPropertyValueType(typeText: String): Boolean =
        ReactCssPropertyShape.looksLikeCssPropertyValueType(typeText)

    /** Single source of truth for "prop never holds a pixel length" — kept in sync with the shape. */
    private fun isNonPixelPropName(prop: String): Boolean =
        ReactCssPropertyShape.isNonPixelPropName(prop)

    /** Resolve helpers that work on XmlAttribute / attribute value style references. */
    fun analyzeStyleObject(attr: XmlAttribute): Verdict {
        val value = attr.valueElement ?: return Verdict(Verdict.Kind.UNKNOWN, "no value")
        val inner = PsiTreeUtil.findChildOfType(value, JSObjectLiteralExpression::class.java)
        if (inner != null && isStyleLikeObject(inner))
            return Verdict(Verdict.Kind.CSS_PROPERTIES_OBJECT, "direct style object literal", inner)
        return analyzeExpression(value as? JSExpression, "style")
    }
}
