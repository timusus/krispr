# kotlinpoet: an annotated use of an alias-imported class ignores the alias, or crashes

kotlinpoet `main` at d4382676. Found while measuring the opt-in operators for Krispr (`NAMED_DEFAULT_DROP`,
since made a default), not in the runs on the target projects.

## The survivor

```
src/jvmMain/kotlin/com/squareup/kotlinpoet/CodeWriter.kt:306  NAMED_DEFAULT_DROP  SURVIVED
    typeName.copy(annotations = emptyList()) → typeName.copy()
    tests: AnnotatedTypeNameTest.annotatedWildcardTypeNameWithExtends, AnnotatedTypeNameTest.annotatedWildcardTypeNameWithSuper, …
```

## What it exposed

The survivor says no test writes an annotated `%T` of a class that has an import alias: keeping the
annotations there changes nothing any test sees. Following it to where the class is recorded for its
alias (`importableType`) shows the same leak is live today one level down. The recorded `ClassName`
keeps its annotations, so an annotated type argument (`List<@Ann Inner>`) does not match the plain
class. With only that use, the file emits `import com.example.Outer.Inner as Al` and still writes
`List<@Ann com.example.Outer.Inner>`. With an annotated and a plain use, `FileSpec.toString()` throws
`IllegalArgumentException: Collection has more than one element`. The fix records the class without
its annotations. The survivor points at the neighbouring line, not at the fix itself.

## Files

- `aliased-annotated-import.patch`: the fix and `AliasedAnnotatedImportTest`, whose two tests fail on
  `main` and pass with the fix; the rest of `:kotlinpoet:jvmTest` (1,009 tests) passes.
