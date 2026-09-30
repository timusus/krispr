# StreetComplete: a point level with a polygon's top or bottom corner counts as inside

streetcomplete/StreetComplete `master` at 6525757, module `app` (run with `targetFiles` on the logic
packages).

## The survivor

```
src/commonMain/kotlin/de/westnordost/streetcomplete/util/math/SphericalEarthMath.kt:406  CONDITION_TRUE  SURVIVED
    lat0 != lat1 && inside(lat, lat0, lat1) → true && inside(lat, lat0, lat1)
    tests: SphericalEarthMathTest.isInMultipolygon, SphericalEarthMathTest.issue2064, SphericalEarthMathTest.point at polygon edge at 180th meridian is in polygon +25
```

## What it exposed

Dropping the check that skips edges parallel to the ray changes nothing any test sees. So no test casts
a ray along an edge or through a corner in a way that matters. Reading `isInPolygon`, a ray through a
vertex always counts one crossing and then skips the next edge. That is right where the outline
continues on the other side of the ray. Where it only touches the ray, as at a top or bottom corner or
at the end of a horizontal edge, the count must be zero or two. So a point to the west of a square,
level with its top edge, is reported inside. The existing rhombus test passes only because its ring
starts at the top corner. `isInPolygon` decides, among other things, whether an address node lies
inside a building for the house number quest. The fix counts an edge only when exactly one of its ends
is above the ray. It also checks points on horizontal edges explicitly, which the old code got right
only by accident of the vertex flag.

## Files

- `point-in-polygon-vertex.patch`: the fix and two tests in `SphericalEarthMathTest` that fail on
  `master` and pass with the fix. With all three StreetComplete patches applied,
  `:app:testAndroidHostTest` passes (2,453 tests).
