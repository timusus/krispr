# Metro: a warning loses its title when its location frame cannot be drawn

ZacSweers/metro `main` at ff71217, module `metro-common`.

## The survivors

```
src/main/kotlin/dev/zacsweers/metro/compiler/diagnostics/render/DiagnosticRenderer.kt:74  CONDITION_TRUE  SURVIVED
    if (!primaryFrameRendered && profile.renderSourceSnippets) → if (true)
    tests: BindingGraphTest.duplicate bindings are an error - same key - same bindings, …
```

Four more survivors sit on the same choice (line 74), and three on the notes and docs lines that follow the
sections (line 103).

## What they exposed

No test renders a diagnostic whose title moves into a source frame, so nothing checks how the renderer
decides it can draw one. Rich output hands the title to the first location section that
`canRenderLocationFrame` says can be framed, and skips printing it on its own. That check counts an item's
code excerpt even when the item prefers a source snippet, which `renderFrames` then ignores; with the source
file unreadable, no frame is drawn and the title is never written. The unused-multibinding warning reports its
contributions exactly this way, so for a source Metro cannot read the warning starts with a bare
`Plugins.kt:3:1` and no message. The fix applies `renderFrames`' rule in the check.

## Files

- `diagnostic-title-lost.patch`: the fix and `DiagnosticRendererTitleTest`, which fails on `main` (the
  output has no title) and passes with the fix. The rest of `:metro-common:test` (224 tests) and the
  compiler's diagnostic tests (17) pass.
