# Bitwarden: the vault's name comparator is not transitive for some letters

bitwarden/android `main` at 1a0c3cb, module `core`.

## The survivors

```
src/main/kotlin/com/bitwarden/core/data/repository/util/SpecialCharWithPrecedenceComparator.kt:36  CONDITION_TRUE  SURVIVED
    c1.isLetter() && c2.isLetter() → true && c2.isLetter()
src/main/kotlin/com/bitwarden/core/data/repository/util/SpecialCharWithPrecedenceComparator.kt:58  CONDITION_TRUE  SURVIVED
    c1.isLetter() && c2.isLetter() && c1.equals(other = c2, ignoreCase = true) → c1.isLetter() && c2.isLetter() && true
    tests: SpecialCharWithPrecedenceComparatorTest.Sorting with comparator should return expected result of sorted string
```

Thirteen more survivors sit on the same case rules (lines 36 and 56 to 65).

## What they exposed

`SpecialCharWithPrecedenceComparator` sorts folders, items, collections and sends. No test pins which
letters it treats as "the same letter". It decides that with `equals(ignoreCase = true)`, which
folds case, but orders every other pair by each character's own uppercase. `İ` (U+0130) equals `I`
ignoring case, yet its uppercase sorts after `J`. So `"İa" < "Ib" < "J" < "İa"`: the comparator is not
transitive. Sorting relies on that contract; the result can depend on the input order, and Java's
sort may throw "Comparison method violates its general contract". The Kelvin sign and `K` behave the
same way. The fix folds each character's case the same way before comparing, which leaves every
other ordering as it was.

## Files

- `comparator-case-folding.patch`: the fix and a test in `SpecialCharWithPrecedenceComparatorTest`
  that fails on `main` (`compare("İa", "J")` is positive) and passes with the fix. The rest of
  `:core:testDebugUnitTest` (81 tests) passes.
