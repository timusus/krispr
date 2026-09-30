# Bitwarden: scanning a card that prints "valid from" fills in that date as the expiry

bitwarden/android `main` at 1a0c3cb, module `ui`.

## The survivor

```
src/main/kotlin/com/bitwarden/ui/platform/feature/cardscanner/util/CardDataParserImpl.kt:44  CONDITION_TRUE  SURVIVED
    panRange?.contains(match.range.first) != true && expiryRange?.contains(match.range.first) != true → panRange?.contains(match.range.first) != true && true
    tests: CardDataParserImplTest.parseCardData extracts CVV when separated from phone number, CardDataParserImplTest.parseCardData extracts four digit CVV for Amex, CardDataParserImplTest.parseCardData extracts standalone CVV not adjacent to other digits +2
```

## What it exposed

Letting the security code overlap the expiry changes nothing any test sees. So the tests don't pin down
which date the parser takes as the expiry. Reading the parser, it takes the first `MM/YY` in the
scanned text. Many debit cards print both dates, as in "VALID FROM 09/21 EXPIRES END 09/26". On those,
the scanner fills in the start date, a date in the past, as the expiry. Every frame gives the same
answer, so scanning longer doesn't help. The fix takes the latest date in the text.

## Files

- `card-scan-valid-from.patch`: the fix and a test in `CardDataParserImplTest` that fails on `main`
  (expiry 09/2021 instead of 09/2026) and passes with the fix. The card scanner's 73 tests pass. In the
  full `:ui:testDebugUnitTest` run, 63 other tests fail with a MockK `StackOverflowError` in this
  container, with and without the fix. None of them are card scanner tests.
