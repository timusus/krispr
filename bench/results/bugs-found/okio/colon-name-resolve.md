# okio: a file name with a colon replaces the whole path on Unix

square/okio `master` at fb4817a, module `okio` (run with `targetFiles`).

## The survivor

```
src/commonMain/kotlin/okio/internal/Path.kt:207  CONDITION_FALSE  SURVIVED
    child.isAbsolute || child.volumeLetter != null → child.isAbsolute || false
    tests: PathTest.absolutePathTraversalWithDivOperator, PathTest.absoluteToAbsolute, PathTest.composingWindowsPath +1688
```

## What it exposed

Nearly 1,700 tests run this line, and none fail when the volume-letter check is dropped. So no test resolves a
child that has a volume letter but isn't absolute. Reading the check shows it applies on every platform. A
Unix name such as `a:b` or `x:1` reads as volume `a:` or `x:`, so `"/tmp".toPath() / "a:b"` returns `a:b`
and loses `/tmp`. Code that writes a file named after a timestamp or a `host:port` then writes it to the
working directory instead. The fix treats `C:x` as another volume only when the path uses backslashes.

## Files

- `colon-name-resolve.patch`: the fix and a test in `PathTest` that fails on `master` (`a:b` instead of
  `/tmp/a:b`) and passes with the fix. `:okio:jvmTest` runs 5,087 tests. Five fail with and without the fix,
  all filesystem-timestamp and `canonicalize(".")` tests that depend on this container.
