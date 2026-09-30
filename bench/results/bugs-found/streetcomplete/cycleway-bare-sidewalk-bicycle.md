# StreetComplete: answering "no cycleway" leaves bare `sidewalk:bicycle=yes` in place

streetcomplete/StreetComplete `master` at 6525757, module `app`.

## The survivor

```
src/commonMain/kotlin/de/westnordost/streetcomplete/osm/cycleway/CyclewayCreator.kt:33  BOOLEAN_ARGUMENT  SURVIVED
    tags.expandSides("sidewalk", "bicycle", false) → tags.expandSides("sidewalk", "bicycle", true)
    tests: CyclewayCreatorKtTest.apply advisory lane answer, CyclewayCreatorKtTest.apply allowed on sidewalk in both directions to existing sidewalk ok, CyclewayCreatorKtTest.apply allowed on sidewalk on one side only +51
```

## What it exposed

Whether the creator reads a bare `sidewalk:bicycle` tag as applying to both sides makes no difference
to any test. The parser does read it that way, so a road tagged `sidewalk:bicycle=yes` and
`sidewalk:bicycle:signed=yes` shows as "cycling allowed on the sidewalk" on both sides. The creator
ignores the bare tags. When a mapper answers "no cycleway" for both sides, the upload keeps them, and
the app reads the road back as allowed on the sidewalk again. The fix expands the bare
`sidewalk:bicycle`, `sidewalk:bicycle:signed` and `sidewalk:oneway:bicycle` tags as the parser does.

## Files

- `cycleway-bare-sidewalk-bicycle.patch`: the fix and a test in `CyclewayCreatorKtTest` that applies the
  answer and parses the result. It fails on `master` (read back as `SIDEWALK_OK`) and passes with the
  fix. The cycleway tests pass with this patch alone. With all three StreetComplete patches applied,
  `:app:testAndroidHostTest` passes (2,453 tests).
