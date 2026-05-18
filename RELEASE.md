# Release Process

## How It Works

Releases are managed using [release-please](https://github.com/googleapis/release-please), an automated GitHub Action
that updates project versions and generates release notes based on commit messages.

1. Merge changes through pull requests with passing checks.
2. Use [Conventional Commits](https://www.conventionalcommits.org/) — commit messages trigger version increments (
   `feat:` → minor, `fix:` → patch, `BREAKING CHANGE` → major).
3. `release-please` creates a release PR updating the version in `pom.xml` and the changelog.
4. When the release PR is merged, a GitHub Release is tagged automatically.

## Versioning

This project follows [Semantic Versioning](https://semver.org/).

For detailed configuration and usage, refer to
the [release-please documentation](https://github.com/googleapis/release-please).
