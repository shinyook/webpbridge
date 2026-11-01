# Releasing webpbridge

## Coordinates and scope

Planned initial release: `io.github.shinyook:webpbridge:0.1.0`. The development POM stays at `0.1.0-SNAPSHOT`; the release workflow changes the version in its isolated checkout. Maven Central publication has not happened yet.

The release JAR bundles exactly these three platform libraries:

| Resource directory | Build runner | Scope |
| --- | --- | --- |
| `linux-x86_64` | Ubuntu 24.04 | glibc-based x86-64 Linux; built against Ubuntu 24.04's runtime, not musl or older glibc |
| `windows-x86_64` | Windows Server 2025 | 64-bit Windows; older Windows versions are not certified |
| `macos-aarch64` | macOS 15 | Apple Silicon; older macOS versions are not certified |

Linux ARM64 and Intel macOS are not bundled in the initial release. Each native is built and tested on its runner, then all three are assembled into one JAR. The assembler rejects missing/duplicate/unexpected native payloads and conflicting libwebp license notices. A single candidate JAR is checked by fresh-JVM integration tests on all three operating systems with JDK 17 and 25 before publication is possible.

## Maven Central setup (maintainer)

1. Sign in at [Central Portal](https://central.sonatype.com) and verify `io.github.shinyook`. [Namespace registration](https://central.sonatype.org/register/namespace/) explains GitHub-based namespaces. `io.shinyook` would require ownership verification for `shinyook.io`.
2. Generate a [Central Portal user token](https://central.sonatype.org/publish/generate-portal-token/). This is separate from a GitHub token or account password.
3. Create or select a dedicated OpenPGP signing key and publish its public key using a [supported public key server](https://central.sonatype.org/publish/requirements/gpg/). Keep the private key and a backup under maintainer control; never commit it or paste it into an issue/chat.
4. Register these [repository Actions secrets](https://github.com/shinyook/webpbridge/settings/secrets/actions):

| Secret | Value |
| --- | --- |
| `MAVEN_CENTRAL_USERNAME` | Portal token username |
| `MAVEN_CENTRAL_PASSWORD` | Portal token password |
| `GPG_PRIVATE_KEY` | ASCII-armored private signing key |
| `GPG_PASSPHRASE` | Signing-key passphrase |

The maintainer is responsible for key ownership, public-key availability, token permissions, and Portal namespace verification. The workflow never prints private keys or token values.

## Candidate validation (no credentials or publishing)

Run **Release candidate and Maven Central** from Actions on the desired commit with:

- `version`: `0.1.0`
- `publish`: `false` (default)

This builds native payloads, assembles the multi-platform JAR, runs regular and packaged-JAR tests, generates source/Javadoc JARs, and uploads the unsigned `release-candidate` artifact with a POM and SHA-256 checksums. It does **not** contact the Central publishing API or require signing credentials. Review the run and candidate artifacts. The development CI still creates single-platform JARs; use this release workflow for the multi-platform candidate.

## Publish an immutable release

Once candidate validation passes and credentials are configured, create and push `v0.1.0` at the exact reviewed commit. Run the release workflow **from that tag**, with matching `version=0.1.0` and `publish=true`.

Publishing is rejected if the tag does not match the stable version. The workflow repeats candidate verification, rebuilds with the same commit timestamp for reproducible JARs, and compares the main, source and Javadoc JARs and POM with the verified candidate before signing and deploying. The dedicated publishing job receives secrets only when `publish=true`. The [official Central publishing plugin](https://central.sonatype.org/publish/publish-portal-maven/) creates checksums, uploads, and waits for publication. A version already published to Central cannot be overwritten; use a new version for corrections.

Signing and authenticated Portal validation cannot be confirmed by an unsigned candidate run. A successful candidate does not mean the release is on Maven Central. After publication, verify retrieval of `io.github.shinyook:webpbridge:0.1.0` using a fresh local Maven repository and run the packaged-JAR checks against that downloaded artifact before announcing availability.

## Local packaging

For a local single-platform package, install the native resource as documented in the root README, then run `mvn -Prelease -Dgpg.skip=true verify`. This checks source/Javadoc generation without signing or publishing. It is not a substitute for the multi-platform candidate workflow. The `central` profile must only be used with the complete native bundle and release credentials.

See [Central publication requirements](https://central.sonatype.org/publish/requirements/) for source/Javadoc, POM metadata and signing requirements.
