package com.github.reactunitconverter.runtime

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import java.io.File

/**
 * Manages the helper file (`src/utils/rem.ts`) that contains `pxToRem` / `pxToVw`
 * functions we generate for **dynamic** inline style values.
 *
 * Responsibilities:
 *   - Locate an existing helper file anywhere in the project (by name and function signature).
 *   - If missing, create `$projectRoot/src/utils/rem.ts` with the stock template.
 *   - Compute a TypeScript import path from a given source file to the helper.
 *   - Ensure a `import { pxToRem } from "<path>"` statement is present when needed.
 */
class PxToRemHelperService(private val project: Project) {

    companion object {
        const val PREFER_HELPER_NAME = "rem.ts"
        const val PREFER_FOLDER = "src/utils"
        const val FUN_PX_TO_REM = "pxToRem"
        const val FUN_PX_TO_VW = "pxToVw"
        const val HELPER_TEMPLATE = "/_templates_/src/utils/rem.ts"

        @JvmStatic fun getInstance(project: Project): PxToRemHelperService =
            project.getService(PxToRemHelperService::class.java)
    }

    /** Find an existing helper VirtualFile, or return null. */
    fun findHelperOrNull(): VirtualFile? {
        val scope = GlobalSearchScope.projectScope(project)
        val candidates = ReadAction.compute<List<VirtualFile>, Throwable> {
            FilenameIndex.getAllFilesByExt(project, "ts", scope)
                .asSequence()
                .filter { it.name == PREFER_HELPER_NAME || it.nameWithoutExtension == "rem" }
                .filter { "utils" in it.path.split('/','\\') }
                .plus(
                    FilenameIndex.getAllFilesByExt(project, "tsx", scope)
                        .asSequence()
                        .filter { it.nameWithoutExtension == "rem" }
                )
                .plus(
                    FilenameIndex.getAllFilesByExt(project, "js", scope)
                        .asSequence()
                        .filter { it.nameWithoutExtension == "rem" }
                )
                .toList()
        }
        // Now verify content actually exports pxToRem
        for (c in candidates) {
            val psi = ReadAction.compute<com.intellij.psi.PsiFile?, Throwable> { PsiManager.getInstance(project).findFile(c) }
                ?: continue
            if (psi.text.contains("export function $FUN_PX_TO_REM") ||
                psi.text.contains("export const $FUN_PX_TO_REM") ||
                psi.text.contains("export var $FUN_PX_TO_REM")) {
                return c
            }
        }
        return null
    }

    /** Creates the helper if missing; returns the file. */
    fun findOrCreateHelper(createIfMissing: Boolean = true): VirtualFile? {
        findHelperOrNull()?.let { return it }
        if (!createIfMissing) return null
        // Try next to known roots.
        val projectRoot = project.basePath?.let { File(it) } ?: run {
            Messages.showWarningDialog(project, "Project base path unknown; cannot create src/utils/rem.ts", "Missing helper")
            return null
        }
        val utilsDir = File(projectRoot, PREFER_FOLDER)
        if (!utilsDir.exists()) utilsDir.mkdirs()
        val target = File(utilsDir, PREFER_HELPER_NAME)
        if (!target.exists()) {
            val url = PxToRemHelperService::class.java.getResource(HELPER_TEMPLATE)
            val content = url?.readText(Charsets.UTF_8) ?: builtinTemplate()
            target.writeText(content)
        }
        LocalFileSystem.getInstance().refresh(true)
        return LocalFileSystem.getInstance().refreshAndFindFileByIoFile(target)
    }

    /** Compute a relative module specifier to import helper from a source file. */
    fun relativeImportSpec(from: VirtualFile, helper: VirtualFile): String {
        val fromDir = from.parent ?: return helper.path
        val rel = VfsUtil.findRelativePath(fromDir, helper, '/') ?: helper.path
        return if (rel.startsWith(".")) rel else "./$rel"
    }

    /** Insert `import { pxToRem[, pxToVw] } from "<spec>"` if not already present. */
    fun ensureImported(
        psiFile: com.intellij.psi.PsiFile,
        helper: VirtualFile,
        needVw: Boolean = false,
        importAlias: String? = null,
    ) {
        val document = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(psiFile.virtualFile)
            ?: return
        val spec = relativeImportSpec(psiFile.virtualFile, helper)
        val named = buildString {
            append(FUN_PX_TO_REM)
            if (needVw) { append(", ").append(FUN_PX_TO_VW) }
        }
        val expectedDefault = """import { $named } from "$spec";"""
        val expectedSingle = """import { $named } from '$spec';"""
        if (document.text.contains(expectedDefault) || document.text.contains(expectedSingle)) return
        // Also tolerate existing import with partial named exports - extend it.
        val existing = Regex("""import\s*\{\s*([^}]*)\s*\}\s*from\s*['"]${Regex.escape(spec)}['"]\s*;?""")
            .find(document.text)
        WriteCommandAction.writeCommandAction(project, psiFile)
            .withName("Add pxToRem helper import")
            .run<Throwable> {
                if (existing != null) {
                    val range = existing.groups[1]!!.range
                    val before = existing.groupValues[1]
                    val newNames = mergeNamed(before, FUN_PX_TO_REM, if (needVw) FUN_PX_TO_VW else null)
                    val startAbs = existing.range.first + existing.value.indexOf('{') + 1
                    val endAbs = startAbs + before.length
                    document.replaceString(startAbs, endAbs, newNames)
                } else {
                    val last = Regex("""^\s*import\s+[^;]+;\s*$""", RegexOption.MULTILINE)
                        .findAll(document.text).lastOrNull()
                    val insertOff = if (last == null) 0 else last.range.endInclusive + 1
                    val prefix = if (insertOff != 0 && !document.text.substring(0, insertOff).endsWith("\n")) "\n" else ""
                    document.insertString(insertOff, prefix + expectedDefault + (if (insertOff == 0) "\n" else "\n"))
                }
            }
    }

    private fun mergeNamed(existing: String, vararg want: String): String {
        val parts = existing.split(',').map { it.trim() }.toMutableList()
        for (name in want) if (name !in parts) parts += name
        return parts.joinToString(", ")
    }

    private fun builtinTemplate(): String = """
/**
 * Convert a number (or "Npx" string) to a `rem` string (1rem = 16px by default).
 * Generated by React Unit Converter plugin.
 */
export function pxToRem(px?: number | string): string | number | undefined | null {
  if (px === null || px === undefined) return px;
  if (typeof px === "string") {
    if (!/^\s*-?\d+(?:\.\d+)?\s*px\s*${'$'}/i.test(px)) return px;
    const parsed = parseFloat(px);
    if (Number.isNaN(parsed)) return px;
    return `${'$'}{parsed / 16}rem`;
  }
  if (typeof px !== "number" || Number.isNaN(px)) return px as unknown as string;
  return `${'$'}{px / 16}rem`;
}

export function pxToVw(px?: number | string, viewportWidth: number = 750): string | number | undefined | null {
  if (px === null || px === undefined) return px;
  if (typeof px === "string") {
    if (!/^\s*-?\d+(?:\.\d+)?\s*px\s*${'$'}/i.test(px)) return px;
    const parsed = parseFloat(px);
    if (Number.isNaN(parsed)) return px;
    return `${'$'}{(parsed * 100) / viewportWidth}vw`;
  }
  if (typeof px !== "number" || Number.isNaN(px)) return px as unknown as string;
  return `${'$'}{(px * 100) / viewportWidth}vw`;
}
""".trimIndent()
}
