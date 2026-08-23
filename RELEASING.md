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
      peripheral; **all 16 tests pass**. Integration tests cannot run in
      CI — they need real hardware. Record the host you ran on in the
      changelog entry (e.g. "verified on adolin / Arch Linux /
      BlueZ 5.86").

      The gated test tasks are configured to never report `UP-TO-DATE` or
      `FROM-CACHE`, so a repeat run on an unchanged commit really does
      re-drive the radio. Sanity-check the wall clock anyway: the live
      suite takes minutes, so a green that comes back in seconds is not a
      hardware run.
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
3. Generate the POM this release is about to publish and run
   `.github/scripts/check-release-docs.sh` against it. That gate fails the
   release — before anything is uploaded — if README's Install coordinate,
   its "This table describes **x.y.z**" sentence, or any row of its
   compatibility table disagrees with the release being cut, or if the
   integration-test count quoted in this file disagrees with the number of
   `@Test` functions in `:integration-tests`. So the README's release-scoped
   claims have to be updated to name the version you are tagging, in the
   commit you tag.
4. Publish the engine artifact to Maven Central via
   `com.vanniktech.maven.publish`.
5. Create a GitHub Release for the tag whose body is the CHANGELOG
   section for this version, with the Maven coordinates prepended.

If any gate fails, the tag remains but nothing publishes. Fix the issue
on `main`, delete the tag (`git push origin :vX.Y.Z-CORE`), and re-tag.
The GitHub Release is only created on a successful publish, so a failed
run leaves no dangling release.

## Required repo secrets

The release workflow needs these GitHub Actions secrets configured:

- `MAVEN_CENTRAL_USERNAME` / `MAVEN_CENTRAL_PASSWORD` — Central Portal
  user token.
- `SIGNING_KEY` — ASCII-armored GPG private key (`gpg --armor
  --export-secret-keys <key-id>`).
- `SIGNING_PASSWORD` — passphrase for that key.
