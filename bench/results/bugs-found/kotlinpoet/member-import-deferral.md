# kotlinpoet: a member-imported type before an indent or statement marker is dropped

kotlinpoet `main` at d4382676. Found while measuring Krispr's opt-in operators, with
`PRECONDITION_REMOVAL` on, not in the runs on the target projects.

## The survivor

```
src/jvmMain/kotlin/com/squareup/kotlinpoet/CodeWriter.kt:314  PRECONDITION_REMOVAL  SURVIVED
    check(deferredTypeName == null) { "pending type for static import?!" } → (removed)
    tests: FileSpecTest.enumAliasedImport, FileSpecTest.importStaticOnce, FileSpecTest.typeNameImportedViaMemberImportRendersCorrectly, …
```

## What it exposed

When a file has a member import from a class (`addImport(System::class, "gc")`), `CodeWriter` holds back
each `%T` of that class until the next plain format part shows whether a member reference follows. It
decides that the next part is plain when it does not start with `%`, but `⇥`, `⇤`, `«` and `»` do not
start with `%` either, and each has a case of its own that never emits the held-back type. So the type
comes out after the following arguments, or not at all when the code block ends there, and its import
goes with it. No test puts a placeholder right after such a `%T`, which is why removing the check that
guards the held-back type changed nothing. The fix defers only when the default case will see the next
part.

`CodeBlock.builder().add("%T", System::class).indent().unindent().build()` inside `addStatement("val s = %L", …)`
renders `val s = ` today; with `.add("%S", "x")` in between it renders `val s = "x"System + 1`.

## Files

- `member-import-deferral.patch`: the fix and `MemberImportDeferralTest`, whose two tests fail on
  `main` and pass with the fix; the rest of `:kotlinpoet:jvmTest` (1,009 tests) passes.
