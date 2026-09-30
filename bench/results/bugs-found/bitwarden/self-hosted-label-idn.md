# Bitwarden: a self-hosted server with an internationalized domain has a blank name

bitwarden/android `main` at 1a0c3cb, module `data`.

## The survivors

```
src/main/kotlin/com/bitwarden/data/repository/util/EnvironmentExtensions.kt:154  ELVIS  SURVIVED
    this.webVault.sanitizeUrl ?: this.base.sanitizeUrl ?: this.api.sanitizeUrl → this.webVault.sanitizeUrl ?: this.base.sanitizeUrl!!
    tests: EnvironmentExtensionsTest.labelOrBaseUrlHost should correctly convert self hosted environment to the correct label
src/main/kotlin/com/bitwarden/data/repository/util/EnvironmentExtensions.kt:154  ELVIS  SURVIVED
    … ?: this.api.sanitizeUrl ?: this.identity.sanitizeUrl → … ?: this.api.sanitizeUrl!!
```

## What they exposed

Only one test reaches the label of a self-hosted server, and it uses one plain ASCII URL, so how the
label is derived is barely pinned. It is `URI.create(url).host`, and `java.net.URI` returns no host for
a domain it does not accept as one, such as the internationalized `https://tresor.müller.de`. The
environment screen accepts that URL (`isValidUri` only checks that `URI.create` does not throw) and the
app connects to it, but the server name on the login, unlock and account screens is empty. The landing
screen matches an account by email and this label, so the same email on two such servers matches the
wrong account. The fix falls back to the host part of the URI's authority.

## Files

- `self-hosted-label-idn.patch`: the fix and a test in `EnvironmentExtensionsTest` that fails on `main`
  (the label is `""`) and passes with the fix. The rest of `:data:testDebugUnitTest` (134 tests) passes.
