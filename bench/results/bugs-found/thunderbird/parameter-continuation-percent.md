# Thunderbird: a bad `%` escape in a later parameter section throws out of the MIME decoder

thunderbird/thunderbird-android `main` at 6987473, module `mail/common`.

## The survivor

```
src/main/java/com/fsck/k9/mail/internet/MimeParameterDecoder.kt:246  CONDITION_FALSE  SURVIVED
    if (parameterSection.section != index || !isExtendedValue && parameterSection is ExtendedValuePar... → if (false)
    tests: MimeParameterDecoderTest.multiple_sections_differently_cased, MimeParameterDecoderTest.multiple_sections_out_of_order, MimeParameterDecoderTest.multiple_sections_switching_between_extended_and_regular_value +2
```

## What it exposed

The survivor says the tests do not check how invalid multi-section RFC 2231 parameters are handled.
Reading that path shows how a section's percent encoding is read. The first section is read inside a
`try`, and the existing tests keep a badly encoded one as a raw parameter. A later section such as
`filename*1*=%zz` is read outside it, so `MimeParameterDecoder.decode` throws "Expected hex character".
None of its callers catch that. They include the lookup of an attachment's file name in
`MessageExtractor` and `BasicPartInfoExtractor`, which runs when a message is saved. The fix treats a
bad later section the way a bad first one is already treated.

## Files

- `parameter-continuation-percent.patch`: the fix and a test in `MimeParameterDecoderTest` that fails
  on `main` ("Expected hex character") and passes with the fix. The rest of `:mail:common:test`
  (333 tests) passes.
