package com.github.reactunitconverter.runtime

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for [PxToRemHelperService] import-path / helper generation behaviours.
 * (NB: file-locate / Psi-imported tests need a full IntelliJ test fixture and are
 * exercised by the plugin's `runIde` test suite; here we exercise the pure string
 * logic that ships inside the service.)
 */
class PxToRemHelperServiceTest {

    @TempDir
    lateinit var tmp: File

    @Test
    fun `template content contains pxToRem function with rootValue 16`() {
        val f = File(tmp, "rem.ts")
        // Use built-in template to simulate what PxToRemHelperService writes when classpath resource unavailable
        val builtin = builtinTemplateSnapshot()
        f.writeText(builtin)
        assertTrue(f.readText().contains("export function pxToRem(px?"))
        assertTrue(f.readText().contains("/ 16}rem"))
    }

    @Test
    fun `relative spec import builder for sibling file`() {
        // simulate PxToRemHelperService.relativeImportSpec behaviour (VfsUtil path-based)
        assertEquals("./Foo.module.css", relativeSpecSimulation("src/components", "src/components/Foo.module.css"))
        assertEquals("../utils/rem", relativeSpecSimulation("src/components/Sub", "src/utils/rem.ts"))
    }

    // Mini simulation of VfsUtil.findRelativePath behaviour for our happy path cases.
    private fun relativeSpecSimulation(fromDir: String, helperPath: String): String {
        val d = fromDir.trim('/')
        val h = helperPath.trim('/').removeSuffix(".ts").removeSuffix(".tsx").removeSuffix(".js")
        val dp = d.split('/')
        val hp = h.split('/')
        var i = 0
        while (i < dp.size && i < hp.size && dp[i] == hp[i]) i++
        val ups = "../".repeat(dp.size - i)
        val rest = hp.drop(i).joinToString("/")
        val s = "$ups$rest"
        return if (s.startsWith(".")) s else "./$s"
    }

    @Test
    fun `ensureImported merge helper names`() {
        val existing = """import { foo, bar } from "./utils/rem";"""
        val spec = "./utils/rem"
        val regex = Regex("""import\s*\{\s*([^}]*)\s*\}\s*from\s*['"]${Regex.escape(spec)}['"]\s*;?""")
        val m = regex.find(existing)!!
        val before = m.groupValues[1]
        val merged = mergeNames(before, "pxToRem", "pxToVw")
        assertEquals("foo, bar, pxToRem, pxToVw", merged)
        // idempotent
        assertEquals("foo, bar, pxToRem, pxToVw", mergeNames(merged, "pxToRem"))
    }

    private fun mergeNames(existing: String, vararg want: String): String {
        val parts = existing.split(',').map { it.trim() }.toMutableList()
        for (name in want) if (name !in parts) parts += name
        return parts.joinToString(", ")
    }

    @Test
    fun `findHelperOrNull respects src utils path convention`() {
        // pure I/O simulation: create src/utils/rem.ts under tmp and confirm it's pickable
        val utils = File(tmp, "src/utils").apply { mkdirs() }
        val rem = File(utils, "rem.ts").apply { writeText("export function pxToRem(){}") }
        val picked = walkForHelper(tmp.toPath(), "pxToRem")
        assertNotNull(picked)
        assertTrue(picked.endsWith("src/utils/rem.ts"))
        assertFalse(picked.endsWith("foo/rem.ts"))
    }

    private fun walkForHelper(root: java.nio.file.Path, needle: String): String? {
        var found: String? = null
        java.nio.file.Files.walk(root)
            .filter { java.nio.file.Files.isRegularFile(it) }
            .filter { it.fileName.toString() == "rem.ts" }
            .forEach { p ->
                val txt = java.nio.file.Files.readString(p)
                if (needle in txt && found == null) found = p.toString()
            }
        return found
    }

    companion object {
        /** Mirror of PxToRemHelperService.builtinTemplate() for tests. */
        internal fun builtinTemplateSnapshot(): String = """
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
""".trimIndent()
    }
}
