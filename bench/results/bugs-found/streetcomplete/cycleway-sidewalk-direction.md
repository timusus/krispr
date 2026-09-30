# StreetComplete: the direction of "cycling allowed on the sidewalk" can't be corrected

streetcomplete/StreetComplete `master` at 6525757, module `app`.

## The survivor

```
src/commonMain/kotlin/de/westnordost/streetcomplete/osm/cycleway/CyclewayCreator.kt:210  CONDITION_TRUE  SURVIVED
    direction != BOTH && tags["sidewalk:$side:oneway:bicycle"] == "no" → direction != BOTH && true
    tests: CyclewayCreatorKtTest.apply advisory lane answer, CyclewayCreatorKtTest.apply allowed on sidewalk on one side only, CyclewayCreatorKtTest.apply allowed on sidewalk to both sides +41
```

## What it exposed

Removing `sidewalk:<side>:oneway:bicycle` whatever its value makes no difference to any test. So no test
changes the direction of a side where cycling is allowed on the sidewalk. Reading that path, the creator
decides whether to write the direction by looking at `cycleway:<side>:oneway`, not at the sidewalk key
it writes. The effects:

- Reversing `sidewalk:right:oneway:bicycle=-1` back to the default direction writes nothing. The upload
  only refreshes the check date, and the app still reads the old direction.
- Choosing "allowed on sidewalk" where a two-way track was leaves `cycleway:right:oneway=no` next to
  `cycleway:right=no`, and the app reads the new answer as two-way.

The fix checks and writes the sidewalk key for this answer. It removes `cycleway:<side>:oneway`,
because `cycleway:<side>` is then `no`. It also removes the sidewalk direction together with the other
sidewalk tags when another answer replaces this one.

## Files

- `cycleway-sidewalk-direction.patch`: the fix and two tests in `CyclewayCreatorKtTest` that apply the
  answer and parse the result. Both fail on `master` (direction `BACKWARD` and `BOTH` instead of
  `FORWARD`) and pass with the fix. It applies on top of `cycleway-bare-sidewalk-bicycle.patch`. With
  all three StreetComplete patches applied, `:app:testAndroidHostTest` passes (2,453 tests).
