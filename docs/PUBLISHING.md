# Publishing Graylog Explorer

The source repository is https://github.com/pOmelchenko/idea-graylog-explorer. Uploading source commits does not publish the plugin to JetBrains Marketplace.

## Release preparation

1. Update `pluginVersion` in `gradle.properties`, change notes and `CHANGELOG.md`. Gradle writes the version into the packaged descriptor; the source `plugin.xml` does not duplicate it. The Python convenience command invokes that same Gradle pipeline.
2. Run `./gradlew check buildPlugin verifyPluginStructure verifyPlugin` with JDK 25. CI must pass against the pinned SDKs. Do not widen `since-build` / `until-build` without validating the new range.
3. Run light/dark isolated UI smoke tests for UI changes. Use only synthetic demo data in screenshots.
4. Review [PRIVACY.md](../PRIVACY.md), the MIT license, vendor contact and naming/trademark permissions. A disclaimer is not a grant of rights to third-party trademarks.
5. Configure ZIP signing, then verify the signature. Never commit a private key or a publication token. A Git/GPG commit signature is separate from the plugin ZIP signature.

## GitHub release builds

The **Release build** workflow uses JDK 25 and the pinned SDKs to run contracts, build the plugin, check its structure and verify PhpStorm/IntelliJ IDEA compatibility. It checks the packaged descriptor ID/version before copying the unchanged Gradle ZIP into `build/release/` and generating a `.zip.sha256` file.

- **Actions → Release build → Run workflow** builds the selected branch or tag and uploads `release-package` for 30 days. Manual runs do not create a GitHub Release, even when a tag is selected. The workflow becomes available for manual dispatch after it is merged into the default branch.
- Pushing a tag exactly matching `v<pluginVersion>` publishes those verified assets in GitHub Releases with generated notes. A mismatched tag fails before the build. A version with a prerelease suffix creates a GitHub prerelease.
- Pull requests changing the release workflow/tooling run the build path and upload the same preview assets; their publication job is skipped.

After the release changes are merged, tag the intended commit. For the current version:

```bash
git switch main
git pull --ff-only
git tag -s v0.17.4 -m "Graylog Explorer 0.17.4"
git push origin v0.17.4
```

Update the version and changelog before subsequent releases. The tag must already exist remotely; the workflow does not create or move tags. Existing releases/assets are not overwritten. If an upload fails and leaves an unpublished draft, inspect/remove that draft before rerunning the publication job. To distribute changed bytes after publication, use a new version and tag.

Verify a downloaded package from the directory containing both assets:

```bash
sha256sum --check graylog-jetbrains-0.17.4.zip.sha256
```

This workflow uses the built-in `GITHUB_TOKEN`: the build has read-only repository access, and only the tag publication job receives `contents: write` plus artifact read access. No Marketplace credentials are required. GitHub release ZIPs are unsigned plugin archives; plugin ZIP signing and Marketplace publication use the separate workflow below.

References: [GitHub workflow events](https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows), [GitHub token permissions](https://docs.github.com/en/actions/tutorials/authenticate-with-github_token), [GitHub CLI release creation](https://cli.github.com/manual/gh_release_create).

## First Marketplace upload

The owner must perform the first upload manually in JetBrains Marketplace:

- Sign in, create/select the Vendor profile and accept the Developer Agreement.
- Complete the required trader/non-trader declaration truthfully for your circumstances.
- Use the real vendor identity and contact from `plugin.xml`.
- Upload the verified plugin ZIP, select the MIT license and link the source repository.
- Add relevant tags, the privacy-policy URL, issue tracker, English description and screenshots.
- Choose the beta channel for initial external testing if desired. Beta users must configure its custom repository in their IDE.
- Wait for Marketplace review. Updates also undergo review; no review deadline is guaranteed.

Metadata URLs:

- Source: https://github.com/pOmelchenko/idea-graylog-explorer
- Issues: https://github.com/pOmelchenko/idea-graylog-explorer/issues
- License: https://github.com/pOmelchenko/idea-graylog-explorer/blob/main/LICENSE
- Privacy: https://github.com/pOmelchenko/idea-graylog-explorer/blob/main/PRIVACY.md

The public plugin ID is `dev.omelchenko.graylogexplorer`. Keep it stable once published. The earlier local prototype used a different ID and must be disabled or uninstalled before using this one.

## Automated updates after the first upload

Configure these repository or `marketplace` environment secrets:

| Secret | Purpose |
| --- | --- |
| `PUBLISH_TOKEN` | JetBrains Marketplace personal access token with publication rights |
| `CERTIFICATE_CHAIN` | PEM certificate chain for plugin ZIP signing |
| `PRIVATE_KEY` | PEM private signing key |
| `PRIVATE_KEY_PASSWORD` | Password for the private key; use an empty value only for an unencrypted key |

The manually invoked **Publish to Marketplace** workflow runs checks, builds, verifies compatibility, signs the ZIP, verifies its signature and publishes it to the selected channel. It requires the first manual upload to have succeeded already. GitHub Release builds use their own tag/manual triggers and do not invoke Marketplace publication.

Use a GitHub `marketplace` environment with appropriate reviewers if you want a human release gate. CI actions are pinned to commit SHAs; Gradle's distribution is checksum-pinned. Update those pins deliberately.

For local signing/publication, the same environment variables are read by Gradle:

```bash
./gradlew check buildPlugin verifyPluginStructure verifyPlugin
./gradlew signPlugin verifyPluginSignature
./gradlew publishPlugin -PreleaseChannel=beta
```

Use `-PreleaseChannel=stable` only for a deliberate public stable release. Each upload must have a unique plugin version. GitHub release tags must match the plugin version as described above.

Official references: [first upload](https://plugins.jetbrains.com/docs/marketplace/uploading-a-new-plugin.html), [approval requirements](https://plugins.jetbrains.com/docs/marketplace/jetbrains-marketplace-approval-guidelines.html), [verification](https://plugins.jetbrains.com/docs/intellij/verifying-plugin-compatibility.html), [signing](https://plugins.jetbrains.com/docs/intellij/plugin-signing.html), [publishing](https://plugins.jetbrains.com/docs/intellij/publishing-plugin.html), [channels](https://plugins.jetbrains.com/docs/marketplace/custom-release-channels.html).
