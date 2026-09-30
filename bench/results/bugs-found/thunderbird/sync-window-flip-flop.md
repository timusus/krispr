# Thunderbird: a message older than the sync window appears and disappears on alternate syncs

thunderbird/thunderbird-android `main` at 6987473, module `backend/imap`.

## The survivor

```
src/main/java/com/fsck/k9/backend/imap/ImapSync.kt:169  CONDITIONALS_BOUNDARY  SURVIVED
    localMessageTimestamp >= earliestTimestamp → localMessageTimestamp > earliestTimestamp
    tests: ImapSyncTest.sync should update flags of existing messages, ImapSyncTest.sync downloading old messages should notify listener with isOldMessage=true, ImapSyncTest.sync should remove messages older than earliestPollDate
```

## What it exposed

The survivor says no test pins which date a local message is compared against when the sync window is
set. Reading the sync shows two different dates. The server's `SINCE` search matches its internal date,
which is the arrival time. The local check uses the stored `date` column, which holds the `Date` header.
A message with an old `Date` header that arrived recently, for example one moved in by another client,
is downloaded on one sync and removed as too old on the next. On the sync after that nothing local
matches, so it is downloaded again. The fix skips a new message whose `Date` is before the sync window,
the same rule the next sync applies.

## Files

- `sync-window-flip-flop.patch`: the fix and a test in `ImapSyncTest` that fails on `main` (the message
  is there after one sync and gone after the next) and passes with the fix. The rest of
  `:backend:imap:test` (28 tests) passes.
