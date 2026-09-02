# Release process

## Versioning

PDF Maker uses Semantic Versioning tags such as `v1.0.0`. `versionName` in `app/build.gradle.kts` must equal the tag
without its `v` prefix. `versionCode` must increase for every Android distribution, including rebuilds.

## Release candidate

1. Finish focused feature/fix commits and their tests.
2. Update `CHANGELOG.md`, `versionName`, and `versionCode` in a dedicated release commit.
3. Confirm CI, CodeQL, dependency review, tests, coverage, lint, and both builds are green on that commit.
4. Create an annotated SemVer tag only from the verified commit.
5. Push the tag and wait for the Release workflow.

The workflow rejects malformed SemVer tags, tags not reachable from `main`, version mismatches, and versions missing
from the changelog. It then repeats the release gates, generates SHA-256 checksums, and creates the GitHub release.

## Artifact meaning

The GitHub workflow publishes an installable debug APK for evaluation and an explicitly named **unsigned** release AAB.
Neither substitutes for a Play-signed production artifact. Production distribution requires the repository owner to
sign with a stable upload key held outside Git and then verify the resulting certificate and checksum.

## Signing rules

- Never commit a keystore, key alias, password, base64 key, or `keystore.properties`.
- Store CI signing material only as protected environment secrets with the narrowest access possible.
- Do not expose signing secrets to pull requests or untrusted workflow code.
- Retain an offline backup of the upload key and document rotation/recovery ownership privately.

## Verification evidence

Keep links to the release commit, CI run, CodeQL run, tag, GitHub release, and published checksums in the release notes.
If any required job fails, fix it in a new commit and create a new version; do not move a published tag.
