# Thunderbird: an autoconfig file with port 0 or a padded hostname throws out of the parser

thunderbird/thunderbird-android `main` at 6987473, module `feature/autodiscovery/autoconfig`.

## The survivor

```
src/main/kotlin/app/k9mail/autodiscovery/autoconfig/RealAutoconfigParser.kt:308  RANGE_BOUNDARY  SURVIVED
    this in 0..65535 → 0 < this && this <= 65535
    tests: RealAutoconfigParserTest.incomingServer with missing authentication should throw, RealAutoconfigParserTest.minimal data, RealAutoconfigParserTest.config with missing 'incomingServer' element should throw +18
```

## What it exposed

Rejecting port 0 changes nothing any test sees, so no test says what port 0 should do. Reading on, the
parser's checks are looser than the values it builds. `isValidPort` accepts 0, but `Port` requires
1 to 65535. `isValidHostname` trims before checking, but the untrimmed text goes to `Hostname`, which
rejects spaces. So `<port>0</port>`, or a hostname with a space or line break around it, passes the
parser's checks and then throws `IllegalArgumentException` from `parseSettings` instead of returning
an error. Neither the parser nor the fetcher catches that. Reading on, the parallel runner's
`coroutineScope` rethrows it, and account setup has no handler above that. So a provider's
autoconfig file can end account setup with an uncaught exception even when another source has
working settings. That last step comes from reading and was not run. The fix passes the trimmed
hostname on and starts valid ports at 1.

## Files

- `autoconfig-port-hostname.patch`: the fix and two tests in `RealAutoconfigParserTest` that fail on
  `main` (`IllegalArgumentException` in both) and pass with the fix. Of the 67 tests in
  `:feature:autodiscovery:autoconfig:test`, the one failure is in `OkHttpFetcherTest`, which needs real
  DNS and fails the same way on `main` behind this container's proxy.
