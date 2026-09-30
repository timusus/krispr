# Setting up Krispr on a project

Apply `id("dev.krispr")` to one module and run `./gradlew :module:krisprRun` **in its own
invocation**. The module can be:

- **Kotlin/JVM** (`org.jetbrains.kotlin.jvm`): `main`, tested by `test`.
- **Android** (`com.android.library`, `com.android.application`, `com.android.dynamic-feature`;
  AGP 9 with its built-in Kotlin, or AGP 8.5.2+ with `kotlin-android`; see [AGP versions](architecture.md#agp-versions)):
  one variant's Kotlin (default `debug`), tested by its
  local unit test task (`testDebugUnitTest`), Robolectric included. The forks use the classpath AGP
  builds for that task: `android.jar` or Robolectric's runtime, the R classes and the unit test
  resource config. Instrumented (on-device) tests are out of scope.
- **Kotlin Multiplatform** (`org.jetbrains.kotlin.multiplatform`): the JVM target's `main`
  compilation, which is `commonMain` + `jvmMain`, tested by `jvmTest`, which runs `commonTest` too.
  Without a JVM target, or with `kotlinTarget` naming it, it uses the Android target instead:
  from `com.android.kotlin.multiplatform.library`, `commonMain` + `androidMain` tested by
  `testAndroidHostTest`; from `com.android.library` with `androidTarget()` (AGP 8), a variant as above. Common code is covered only through that one JVM-hosted target. Native, JS
  and Wasm tests are out of scope.

When several modules apply it, declare it once in the root build with `id("dev.krispr") apply false`,
as Gradle recommends for any plugin used by more than one module. The modules then share one copy of the
plugin, and with it the build-wide limit on test JVMs. Without that, Gradle may load a copy per module,
and the shared limit fails with a build service type mismatch. Builds with isolated projects work.

Until the plugin is published, a composite build is the way to get it:

```kotlin
// settings.gradle.kts
pluginManagement { includeBuild("../krispr") }
includeBuild("../krispr") // substitutes dev.krispr:krispr-compiler-<variant> and krispr-runtime
```

Settings, all optional:

```kotlin
krispr {
    timeoutFactor = 1.25           // per-mutant timeout = (its tests' recorded time + start-up) * factor + constant;
    timeoutConstantMillis = 4000L  // 1.25 and 4000 are PIT's defaults
    timeoutMinimumMillis = 10000L  // no mutant run times out sooner; default 10000 with Robolectric tests (a fresh JVM's start-up on a busy host), none without
    operators = listOf("DEFAULTS", "SWAP_COLLECTION_CALL") // or -Pkrispr.operators=...; see [PHILOSOPHY.md](PHILOSOPHY.md#operators)
    historyFile = file("ci-cache/krispr-history.json") // default build/krispr/history.json
    useHistory = false             // or -Pkrispr.history=false: run every mutant, keep no history
    maxConcurrentJvms = 4          // krispr JVMs running at once across the whole build (or -Pkrispr.maxConcurrentJvms=4);
                                   // default 0 is half the cores, capped by memory
    threads = 2                    // an optional lower limit for this module alone; default 0 is maxConcurrentJvms
    testProject = ":test"          // run another module's JVM tests, when tests live apart from the code
    androidVariant = "demoDebug"   // Android: the variant to mutate and test; default "debug"
    kotlinTarget = "android"       // KMP: which JVM or Android target; default the single JVM target
    mutate = listOf("toString")    // kinds of code skipped by default to mutate anyway; see tuning.md
}
```

The project must be on Kotlin 2.1.20 up to 2.4.x (see [Kotlin versions](#kotlin-versions)). The test task can
use JUnit 4 (`useJUnit()`, `kotlin-test-junit`) or the JUnit Platform (Jupiter, Kotest, Vintage).
For JUnit 4, Krispr brings its own Platform launcher and Vintage engine. A JUnit Platform task that doesn't
declare the launcher, which Gradle before 9 supplies itself, gets the launcher matching its engine.

## Kotlin Multiplatform

Krispr mutates Kotlin IR, which all backends share, so it mutates `commonMain` itself: the report
names `src/commonMain/kotlin/...` files and lines, not JVM classes. PIT and other bytecode tools only
see one JVM compilation's output.

- **How it runs.** The compiler plugin instruments one JVM-hosted target's `main` compilation
  (`commonMain` + `jvmMain`, or `commonMain` + `androidMain`), and `commonTest` runs against it through
  that target's test task (`jvmTest`, `testAndroidHostTest`). Choose with `kotlinTarget`; the default is
  the single `jvm()` target, else the Android target.
- **Other targets.** `js`, `wasmJs`, native and Apple targets in the same module still build normally
  and are never instrumented. A module with no JVM or Android target fails `krisprRun` with a message
  saying so; add `jvm()` to mutation-test its common code.
- **Try it.** `cd sample-kmp && ../gradlew krisprRun` (JVM, Android, JS and macOS targets; a `Flow`
  pipeline and deliberately weak tests), or `-PkrisprTarget=android`.
- **Native, JS and Wasm test runs** are not supported yet. A probe compiled the same mutants with the
  unchanged compiler plugin for JS, Wasm and macOS, got identical mutant ids on every backend, and ran
  `commonTest` once per mutant on each with the expected verdicts. What remains is a multiplatform
  runtime and a test runner per backend; see [kmp.md](kmp.md) for the results and an
  estimate.

## Kotlin versions

Krispr runs on Kotlin 2.1.20 through 2.4.x. The IR API a compiler plugin uses is not binary compatible
between Kotlin releases, not even between feature releases of one minor: a plugin compiled against
2.3.0 fails on 2.3.20 with `NoSuchMethodError`. So, like the Compose compiler and KSP, the compiler
plugin ships once per range of releases, each built against the oldest release it serves:

| Artifact | Kotlin |
|---|---|
| `krispr-compiler-k2120` | 2.1.20 – 2.1.x |
| `krispr-compiler-k220` | 2.2.x |
| `krispr-compiler-k230` | 2.3.0 – 2.3.1x |
| `krispr-compiler-k2320` | 2.3.20 – 2.3.x |
| `krispr-compiler-k240` | 2.4.x |

The Gradle plugin picks the artifact from the Kotlin compiler version the build actually resolves
(`kotlin-compiler-embeddable` on the `kotlinCompilerClasspath` or `compileClasspath` configuration,
falling back to the Kotlin Gradle plugin's own version when neither resolves one), and fails the
build with the supported range when it has none. The floor is 2.1.20 because Krispr reads and
writes calls through the unified parameter and argument API (`IrFunction.parameters`,
`IrCall.arguments`), which 2.1.0 and 2.1.10 do not have. Before 2.3.0 there is no
`-Xcompiler-plugin-order`, so on 2.1 and 2.2 Krispr cannot ask to run ahead of the Compose
compiler; it still leaves Compose's own code alone (see [Android and Compose](architecture.md#android-and-compose)).

`krispr-compiler/src` holds the shared sources and tests, and each `krispr-compiler/k*` directory
holds a variant's build file and its `VersionCompat.kt`, the few APIs that differ (plugin id, symbol
lookup). The `krispr.compiler-variant` convention plugin in `build-logic` compiles each variant and
runs the shared compiler tests on the oldest and newest release of its range (`test` and
`testKotlin<version>`, both part of `check`). To try the JVM sample on another release, pass
`-Psample.kotlin=2.1.20` (for example) to its build.

Pass `-Pkrispr.fullMatrix=true` to also run the shared compiler tests against a middle patch release
of a range, where one exists between its tested endpoints (currently `k220` on 2.2.10 and `k240` on
2.4.10, as `testKotlin2_2_10`/`testKotlin2_4_10`). This is off by default because it roughly doubles
those variants' test time for a release the endpoints already cover most of; CI can opt in.
