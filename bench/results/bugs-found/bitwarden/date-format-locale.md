# Bitwarden: dates take the locale's pattern but the device's month names

bitwarden/android `main` at 1a0c3cb, module `core`.

## The survivor

```
src/main/kotlin/com/bitwarden/core/data/util/TemporalAccessorExtensions.kt:34  NAMED_DEFAULT_DROP  SURVIVED
    …gy.INSTANCE, locale, ), clock = clock, ) → …gy.INSTANCE, locale, ), )
    tests: TemporalAccessorExtensionsTest.toFormattedDateStyle should return correctly formatted string with with locale
```

## What it exposed

The survivor says the tests cannot tell which arguments `toFormattedDateStyle` passes on to
`toFormattedPattern`. Reading that call shows the one it does not pass: the locale. The locale picks
the pattern (`MMMM d, y` for US English), but the pattern is then formatted with the default locale, so
month names, day names and AM/PM come from the device. The tests only use US and UK English on an
English JVM, where the two agree. The plan screen formats billing dates with `Locale.US`, so on a French
device it shows "décembre 10, 2023". The fix passes the locale to the formatter.

## Files

- `date-format-locale.patch`: the fix and a test in `TemporalAccessorExtensionsTest` that fails on
  `main` ("décembre 10, 2023" instead of "December 10, 2023") and passes with the fix. The rest of
  `:core:testDebugUnitTest` (81 tests) passes.
