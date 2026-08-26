# Releasing

1. Update `VERSION_NAME` and `CHANGELOG.md`.
2. Run unit tests, lint, release assembly, and `publishToMavenLocal`.
3. Push a signed tag named `v<VERSION_NAME>`.
4. The publish workflow uploads signed artifacts to Central Portal.
5. Inspect the deployment and validation results in Central Portal, then publish manually.
6. Verify both public dependencies from Maven Central in a clean consumer project.
7. Create the matching GitHub Release using the changelog entry.

Required GitHub secrets:

- `MAVEN_CENTRAL_USERNAME`
- `MAVEN_CENTRAL_PASSWORD`
- `SIGNING_KEY`
- `SIGNING_PASSWORD`

Maven Central releases are immutable. Never reuse a published version.
