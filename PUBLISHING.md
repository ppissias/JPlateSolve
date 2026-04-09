# Publishing

This repository uses a repeatable Maven Central workflow that can be reused in
other Java libraries.

## Local configuration

1. Keep the real `gradle.properties` local-only.
2. Create `gradle.properties` in the project root or `%USERPROFILE%\.gradle\gradle.properties`.
3. Fill in:
   - `pomDeveloperId`
   - `pomDeveloperName`
   - `pomDeveloperEmail`
   - `pomDeveloperOrganization`
   - `pomDeveloperOrganizationUrl`
4. Configure signing using one of these modes:
   - Preferred: `signingKey` and `signingPassword`
   - Legacy: `signing.keyId`, `signing.password`, and `signing.secretKeyRingFile`

## Standard commands

Use the same command set across repositories:

- `./gradlew test`
- `./gradlew integrationTest`
- `./gradlew publishToMavenLocal`
- `./gradlew clean mavenCentralBundle`

What they do:

- `test`: offline, deterministic unit tests
- `integrationTest`: opt-in live or external-service verification
- `publishToMavenLocal`: verify the publication shape locally without requiring signing
- `mavenCentralBundle`: build the signed Maven Central Portal upload zip

## Release flow

1. Update the project version in [build.gradle](build.gradle).
2. Run `./gradlew test`.
3. If the project uses external services, run `./gradlew integrationTest`.
4. Run `./gradlew publishToMavenLocal`.
5. Run `./gradlew clean mavenCentralBundle`.
6. Upload `build/distributions/*-central-bundle.zip` to the Central Portal.
7. Tag the release after the Portal accepts the bundle.

## Reuse in other repos

For `JTransient`, `SpacePixels`, or any other Java library, keep these conventions the same:

- Ignore real `gradle.properties`
- Use the same property names for signing and developer metadata
- Keep `integrationTest` as the opt-in external/live verification task
- Keep `mavenCentralBundle` as the standard release bundle command
- Keep `publishToMavenLocal` as the local publication smoke test

Only the repo-specific values should change:

- `group`
- `artifactId`
- project description
- SCM URL
- license metadata
- default developer metadata if needed
