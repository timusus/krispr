# Known gaps

- **Android**: only local unit tests. Instrumented and on-device tests, `testProject`, and
  mutating more than one variant per run are not supported. Paparazzi's `doFirst` settings are not
  reproduced, which matters only with `useScreenshotTests`. Robolectric 4.17 cannot set up SDK 36 on JDK 21; the sample pins `sdk=35`.
- **One JVM-hosted target, one test task.** Only a module's own JVM or Android unit tests are used,
  or those of one `testProject`. JS, Wasm and native tests of a multiplatform module do not count
  (see [kmp.md](kmp.md)).
  TestNG is not supported.
  A test task left on Gradle's default JUnit 4 framework gets the Vintage engine in krispr's runs; if its
  classpath has no `junit:junit` (a module with no JVM tests of its own), the run fails with Vintage's
  "junit-vintage-engine is present but junit:junit is not" instead of saying there are no tests. Point
  `testProject` at the module whose tests exercise the code.
- **Cross-module test runs** compile the test project against the instrumented jar in its normal
  build directory. Its next normal build recompiles, because that input has changed.
- **Filters**: an `--tests`/`filter` include that names a method selects the whole class, and a
  method-level exclude is ignored.
- **Incremental**: the instrumented compilation is not incremental, because the manifest is written
  per compilation. Verdicts are reused (see [Incremental runs](usage.md#incremental-runs)), but the build and the
  recording run still happen every time.
- **Equivalent mutants**: only swaps a literal makes equivalent are filtered (`x + 0`, `x * 1`, `x / -1`;
  see [Code that isn't worth mutating](tuning.md#code-that-isnt-worth-mutating)). In the validation run, about half of the sampled survivors were
  equivalent. Common cases:
  - boundary flips on assignments that give the same value
  - `> 0` → `>= 0` where the value is never 0
  - `&&`/`||` flips hidden by an earlier branch
  - collection-capacity arithmetic
- **Not mutated**: stepped ranges iterated by a `for` loop, collections and `indices` it iterates,
  string templates, and return values other than Boolean, Int, nullable and (opt-in) empty ones. Equality checks in a subject `when` are
  mutated, but their descriptions are poor. Hand-written `equals` and `hashCode` overrides are skipped
  unless `mutate` lists `equalsHashCode`; the comparison with PIT
  ([evidence.md](evidence.md#does-krispr-miss-what-pit-finds)) found a real gap in kotlin-result's
  `Failure.equals`, and a kotlinpoet bug fix inside `TypeVariableName.equals`/`hashCode` had no mutant on its
  lines. turbine's `withTurbineTimeout` returning `null` for a generic `T` is a gap no return-value operator
  covers.
- **K2 API stability**: the plugin uses `IrElementTransformerVoidWithContext`,
  `DeclarationIrBuilder` and the `@UnsafeDuringIrConstructionAPI` symbol owners. These are internal
  compiler APIs with no compatibility promise, and each Kotlin minor may break them. Kotlin is
  pinned to 2.4.20.
- **Reused JVMs isolate the build's classes only.** A library that loads project classes through
  its own classloader, or state kept in the JDK or a library, is shared between the mutants a worker
  runs. The baseline check catches tests that depend on it, not mutants that disturb it. In a reused
  Robolectric sandbox the project's classes are loaded once for every mutant the worker runs, so a
  `lazy` or `object` value computed while a surviving mutant was active is what later mutants see.
  Survivors are confirmed in a fresh JVM, but a kill that such a value caused is
  not, unless `confirmKills` is on. Set `robolectric = "fresh"`, or `reuseJvms = false`, if
  statuses look wrong.
- **Screenshot detection is by constant pool.** Only a test class that uses a screenshot library
  directly counts, so a test that captures through its own helper (nowinandroid's `captureMultiTheme`)
  is a killer like any other; add it to `excludeTests`. `useScreenshotTests = true` turns detection
  off. The recording run still runs every test.
