# Bugs found by reading survivors

Krispr ran on the logic modules of six open-source Android and Kotlin projects, with default settings
plus `mutate = listOf("equalsHashCode")` for Bitwarden and okio. Each survivor was read, and where one
pointed at code that looked wrong, the bug was confirmed with a test that fails on the project's main
branch. Each folder has, per bug, the survivor as Krispr printed it, one paragraph on what reading it
turned up, and a patch with the fix and that test. No upstream reports or pull requests were opened.

| Project | Commit | Modules run | Mutants | Survived | Bugs |
|---|---|---|---|---|---|
| bitwarden/android | 1a0c3cb | `core`, `network`, `data`, `ui` | 1,855 | 376 | 4 |
| thunderbird/thunderbird-android | 6987473 | `mail/common`, `mail/protocols/imap`, `backend/imap`, `core/common`, `feature/autodiscovery/autoconfig` | 3,818 | 862 | 5 |
| streetcomplete/StreetComplete | 6525757 | `app`, logic packages only (`targetFiles`) | 3,972 | 505 | 3 |
| ZacSweers/metro | ff71217 | `metro-common` | 2,102 | 438 | 2 |
| square/okio | fb4817a | `okio`, JVM, selected files (`targetFiles`) | 1,294 | 150 | 1 |
| duckduckgo/Android | dea3657 | `privacy-config-impl`, `cookies-impl`, `autofill-impl` | 6,387 | 284 | 0 |

The two kotlinpoet bugs were found earlier, while measuring the opt-in operators, not in these runs.

## What could not be run

- Bitwarden's `:app` module, where the vault, URI matching, the password generator and TOTP live, needs
  the Bitwarden SDK from GitHub Packages, which needs credentials this machine doesn't have.
- DuckDuckGo's tracker detection is in `:app`, which was left out as an app module. Three feature
  modules were run instead.

## Candidates that were not confirmed

- DuckDuckGo autofill never offers a login saved on an `http://` page back on that page. Stored domains
  are compared as `https`, so the default ports differ. That may be a deliberate refusal to fill over
  plain HTTP, and only the maintainers can say.
- Thunderbird's IMAP idler can lose a `stop()` that arrives before the first `IDLE`, because `idle()`
  resets the flag. It is a race, and no reliable failing test was written for it.
- Thunderbird saves the highest known UID in a `finally`, even when the sync fails, which suppresses
  new-mail notifications for messages fetched on the next sync. This looks deliberate.

## What the runs exposed in Krispr

- A test task that uses the JUnit Platform on Gradle 8 gets its launcher from Gradle, so Krispr's forks
  had none. DuckDuckGo's three modules failed until the launcher was declared as a test dependency.
- The plugin does not work with Gradle's isolated projects. Thunderbird was run with them turned off.
- `krisprRecord` stops the run when any test fails with no mutant active. Tests that need the network,
  or that depend on the order they run in, had to be excluded by class.
- In DuckDuckGo's `autofill-impl`, 415 mutants are unknown because a test failed without the mutant
  active, which points at tests that depend on order or timing.
