# Release Process

## Version scheme

Releases are tagged `v<ours>-<blue-falcon-core>`, e.g. `v1.0.0-3.0.3`.
The tag (minus the leading `v`) must exactly match the `version=` field
in `gradle.properties`. The release workflow enforces this.

## Pre-release checklist

Every release requires **all** of the following on the commit you intend
to tag:

- [ ] `:engine:build` green on GitHub Actions on that commit.
- [ ] `./gradlew :integration-tests:linuxX64Test -PrunIntegrationTests=true`
      run against a live [BF-Test](https://github.com/Monkopedia/bf-test-peripheral)
      peripheral; **all 14 tests pass**. Integration tests cannot run in
      CI — they need real hardware. Record the host you ran on in the
      changelog entry (e.g. "verified on adolin / Arch Linux /
      BlueZ 5.86").
- [ ] `gradle.properties` `version=` matches the tag you're about to
      push (without the `v` prefix).
- [ ] `CHANGELOG.md` has a dated `## [x.y.z-core] - YYYY-MM-DD` heading
      for this release — move the content from `[Unreleased]` into the
      new section and keep an empty `[Unreleased]` at the top.

## Cutting the release

```bash
# Once the checklist is done and pushed to main:
git tag -a vX.Y.Z-CORE -m "Release X.Y.Z-CORE"
git push origin vX.Y.Z-CORE
```

The `release.yml` workflow will:

1. Verify the tag matches `gradle.properties` `version=`.
2. Verify `CHANGELOG.md` has a dated entry for the version.
3. Publish the engine artifact to Maven Central via
   `com.vanniktech.maven.publish`.

If any gate fails, the tag remains but nothing publishes. Fix the issue
on `main`, delete the tag (`git push origin :vX.Y.Z-CORE`), and re-tag.

## Required repo secrets

The release workflow needs these GitHub Actions secrets configured:

- `MAVEN_CENTRAL_USERNAME` / `MAVEN_CENTRAL_PASSWORD` — Central Portal
  user token.
- `SIGNING_KEY` — ASCII-armored GPG private key (`gpg --armor
  --export-secret-keys <key-id>`).
- `SIGNING_PASSWORD` — passphrase for that key.
