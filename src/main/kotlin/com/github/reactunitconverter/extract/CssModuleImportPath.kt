package com.github.reactunitconverter.extract

/**
 * Pure path logic for CSS Module import specifiers — IDE/PSI-free so it can be unit-tested
 * headless. Used by [com.github.reactunitconverter.action.ExtractToCssModuleAction] when
 * writing `import styles from '<spec>'` after extracting an inline style to a `.module.css`.
 */
object CssModuleImportPath {

    /**
     * Normalize a relative path returned by `VfsUtil.findRelativePath` into a valid ES
     * module specifier (Bug #5). `findRelativePath` may return:
     *   - `"Foo.module.css"`        (same directory)  → `"./Foo.module.css"`
     *   - `"../css/Foo.module.css"` (parent/sibling)  → keep `../css/Foo.module.css`
     *     (never `./../...` — that path is invalid and neither TS nor the bundler resolves it)
     *   - `"./sub/Foo.module.css"`  (already relative) → unchanged
     *   - `"/abs/path/Foo.module.css"` (absolute fallback) → unchanged
     */
    @JvmStatic
    fun specifier(rel: String): String = when {
        rel.startsWith("./") || rel.startsWith("../") || rel.startsWith("/") -> rel
        else -> "./$rel"
    }
}
