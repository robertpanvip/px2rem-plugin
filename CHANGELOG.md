# Changelog

All notable changes to the React Unit Converter (px2rem/vw) plugin.

## [1.0.0] - 2026-08-13

- Initial public release.
- Auto-detect px2rem / postcss-pxtorem configs from PostCSS, Vite, Rsbuild, package.json, and legacy rc files.
- Skip CSS files when a PostCSS pxtorem CSS plugin is detected; only operate on React inline styles.
- Convert `style={{ ... }}` px values to rem / vw / vh / em using detected rootValue / viewportWidth / propList / minPixelValue / unitPrecision.
- Inspection "React inline style px should convert" with local quickfix.
- Completion contributor that offers `"Nrem"`/`"Nvw"` variants derived from a typed `"Npx"` literal inside a style property.
- Extract-to-CSS-Module refactoring:
    - Resolve/suggest creation of `SameName.module.css/.less/.scss`.
    - Semantic class-name inference (className, parent/siblings, tag, ids, aria-label/role, style hints, variant inference).
    - Interactive rename dialog with live CSS + JSX preview and validation.
    - Replaces style attribute, manages spreads correctly, adds module import statement.
