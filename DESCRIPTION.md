# React Unit Converter Plugin Description

A JetBrains IDE (IntelliJ IDEA Ultimate / WebStorm / PhpStorm etc.) plugin for **React / TSX / JSX** projects that makes it easy to work with responsive units (`rem`, `vw`, etc.) inside **inline styles** (`style={{ ... }}`), while leaving CSS files untouched when a PostCSS `pxtorem` build-time plugin is already configured.

## Highlights

### 1. Project px2rem config auto-detection

Reads configuration from:
- **PostCSS**: `postcss.config.js/.ts/.cjs/.mjs`, `.postcssrc*`, and `package.json#postcss` → the `postcss-pxtorem` plugin options.
- **Vite**: `vite.config.js/ts/cjs/mjs` → `vite-plugin-px2rem` options and/or inline `css.postcss.plugins` pxtorem options.
- **Rsbuild**: `rsbuild.config.*` → `@rsbuild/plugin-px2rem` options.
- Legacy: `.px2remrc`, `px2rem.config.js`, `package.json#px2rem` field.

When a build-time CSS plugin is detected (`cssLevelPluginEnabled=true`), the plugin **skips CSS files on purpose** and only operates on React inline styles, matching the intent: postcss-pxtorem already converts CSS for you — the inline styles are the gap.

All fields are honored:
`unitToConvert` (`rem` / `vw` / `vh` / `em`), `rootValue`, `unitPrecision`, `propList` (with `*` wildcard and `!prop` exclusion), `minPixelValue`, `viewportWidth`, `selectorBlackList`, `exclude`, `replace`, `mediaQuery`.

### 2. px → rem/vw conversion for React inline styles

The action `React Unit Converter → Convert px in inline styles` (or the weak-warning inspection with quickfix) only touches the value side of `React.CSSProperties`:
- Quoted `"Npx"` strings.
- Bare numbers (React interprets these as px for layout properties, except `zIndex`, `fontWeight`, `opacity`, `flex*`, `order`, `lineClamp`, `columns`, etc. which are kept numeric).
- `calc(Npx + …)` inner px values.
- Follows `propList` allow/deny rules and `minPixelValue`.

Offers code completion entries like `"1rem (from 16px)"` / `"10vw (from 75px)"` while typing inside a style property literal.

### 3. Extract inline styles to CSS Module

The action `React Unit Converter → Extract inline style to CSS Module`:

1. Finds/creates `SameName.module.css/.less/.scss` next to the current TSX file.
2. **Infers a semantic class name** from context: existing `className` / parent `className` / sibling classNames (with structural variant inference — `header` → `body` → `footer` etc.), tag name (`Button`, `FormInput`…), `id` / `data-testid` / `aria-label` / `role`, and style properties themselves (flex, rounded, shadow, padded, size tiers, and so on).
3. Shows an **interactive rename dialog** with live CSS + JSX preview and validation (no duplicates, valid identifier, required) so you can rename before applying.
4. Appends the rule to the module file, replaces `style={{ … }}` with `style={styles.className}` (when the object contains `…spreads`, emits `style={{ …styles.foo, …spreadA }}`), and inserts `import styles from "./xxx.module.css"` if missing.
5. Opens the CSS module in the editor after the refactor.

### 4. Settings & overrides

- `Settings → Tools → React Unit Converter` (global): default fallback values, CSS Module import identifier, and UI behaviour toggles.
- `Settings → Tools → React Unit Converter Project` (per-project): shows the last auto-detected source, values, allows override per field, and has a one-click **Re-detect from project** action.
- Project auto-startup trigger re-detects the config when a project is opened; cached values refresh every ~10 minutes.
