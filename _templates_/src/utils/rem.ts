/**
 * Convert a number (or "Npx" string) to a `rem` string, based on 1rem = 16px.
 *
 * This helper is intentionally small and dependency-free so the generated helper
 * does not need to track project rootValue at runtime. The IDE plugin uses the
 * project's px2rem detection (rootValue/viewportWidth/minPixelValue etc.) and only
 * emits `pxToRem(...)` wrappers for **dynamic** values where rootValue would
 * otherwise be unknown at rewrite time. For static values the plugin will inline
 * the converted unit (rem/vw/...) directly.
 *
 * Place me at `src/utils/rem.ts` (or any aliased `@/utils/rem` the project uses).
 */
export function pxToRem(px?: number | string): string | number | undefined | null {
  if (px === null || px === undefined) {
    return px;
  }
  if (typeof px === "string") {
    // Already has a unit or blank - return as-is.
    if (!/^\s*-?\d+(?:\.\d+)?\s*px\s*$/i.test(px)) {
      return px;
    }
    const parsed = parseFloat(px);
    if (Number.isNaN(parsed)) return px;
    return `${parsed / 16}rem`;
  }
  if (typeof px !== "number" || Number.isNaN(px)) {
    // Fallthrough - keep unknown types unchanged so TypeScript still compiles.
    return px as unknown as string;
  }
  return `${px / 16}rem`;
}

/** Same helper but using project viewportWidth 750 (plugin default for vw projects). */
export function pxToVw(px?: number | string, viewportWidth: number = 750): string | number | undefined | null {
  if (px === null || px === undefined) return px;
  if (typeof px === "string") {
    if (!/^\s*-?\d+(?:\.\d+)?\s*px\s*$/i.test(px)) return px;
    const parsed = parseFloat(px);
    if (Number.isNaN(parsed)) return px;
    return `${(parsed * 100) / viewportWidth}vw`;
  }
  if (typeof px !== "number" || Number.isNaN(px)) return px as unknown as string;
  return `${(px * 100) / viewportWidth}vw`;
}
