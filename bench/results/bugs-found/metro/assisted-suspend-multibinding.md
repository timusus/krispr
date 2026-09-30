# Metro: a suspend multibinding behind an assisted target is reported twice, once with wrong advice

ZacSweers/metro `main` at ff71217, module `metro-common`.

## The survivors

```
src/main/kotlin/dev/zacsweers/metro/compiler/graph/SuspendBindingValidator.kt:391  CONDITION_FALSE  SURVIVED
    if (dependency.typeKey in suspendMultibindings) → if (false)
src/main/kotlin/dev/zacsweers/metro/compiler/graph/SuspendBindingValidator.kt:414  CONDITION_FALSE  SURVIVED
    if (dependency.typeKey !in analysis.suspendKeys) → if (false)
    tests: SuspendBindingValidatorTest.assisted targets can consume maps of suspend providers, …
```

## What they exposed

The first survivor says no test pins why a Provider- or Lazy-wrapped dependency on a multibinding over
suspend bindings is skipped: `validateMultibindings` already reports it with a more specific message. The
second sits in the matching loop for assisted targets, which has no such skip. So an `@AssistedInject`
target with a `Provider<Set<Plugin>>` (or `Lazy<Map<…>>`) parameter, where one `@IntoSet` contribution is
`suspend`, gets two errors: the multibinding one, and `SUSPEND_BINDING_WRAPPED_IN_PROVIDER`, which says to
use `suspend () -> Set<Plugin>` instead. That advice does not work: a multibinding over suspend bindings
is unsupported in any form. The fix gives the assisted loop the same skip.

## Files

- `assisted-suspend-multibinding.patch`: the fix and a test in `SuspendBindingValidatorTest` that fails on
  `main` (`[MULTIBINDING_OVER_SUSPEND_BINDINGS, SUSPEND_BINDING_WRAPPED_IN_PROVIDER]`) and passes with the
  fix. The rest of `:metro-common:test` (223 tests) passes; the compiler's end-to-end tests were not run.
