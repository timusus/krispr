# Thunderbird: a message identifier with a one-character part between dots is rejected

thunderbird/thunderbird-android `main` at 6987473, module `mail/common`.

## The survivor

```
src/main/java/com/fsck/k9/mail/internet/MessageIdParser.kt:192  CONDITION_TRUE  SURVIVED
    if (!endReached() && predicate(peek())) → if (true)
    tests: MessageIdParserTest.empty input, MessageIdParserTest.message identifier containing only left side, MessageIdParserTest.message identifier missing angle brackets +18
```

## What it exposed

With the character check in `expect` turned off, every test still passes. So no test pins which
characters the parser accepts inside an identifier. Reading `readDotAtom`, the loop reads one character
and an optional dot and one more character, then continues only if the next character is not a dot.
After a one-character part such as the `1` in `<20240930031400.1.I4f2a@changeid>` it stops at the dot
and reports "Expected '@'". git send-email and patman produce identifiers of that shape, and so does
a host name like `mail.x.example`. The parser's caller reads the In-Reply-To value of a `mailto:`
link, which mailing-list archives put on their reply links. It logs the error and drops the value, so
the reply is not threaded. The fix reads any run of characters and dots, with a character after each dot.

## Files

- `message-id-dot-atom.patch`: the fix and a test in `MessageIdParserTest` that fails on `main`
  ("Expected '@'") and passes with the fix. The rest of `:mail:common:test` (332 tests) passes.
