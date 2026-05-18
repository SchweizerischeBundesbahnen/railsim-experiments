# Contributing

Thank you for contributing to Railsim Experiments.

This document describes how to propose changes, report issues, and work with the maintainers.

## Code of Conduct

Participation in this repository is governed by the [Code of Conduct](CODE_OF_CONDUCT.md).

## Before You Start

- Read the [README.md](README.md) for project setup and scope.
- Review the [CODING_STANDARDS.md](CODING_STANDARDS.md) file before submitting code changes.

## Questions and Support

- Use GitHub Issues for questions and support.
- Do not use public issues for private security reports. Follow the organization-level security reporting instructions.

## Reporting Bugs

When reporting a bug, include:

- A concise summary of the problem.
- Exact steps to reproduce it.
- Expected behavior and actual behavior.
- Environment details (OS, JDK version).
- Logs or a minimal reproduction when available.

## Requesting Features

- Explain the problem you want to solve, not only the proposed solution.
- For large changes, open an issue before implementing code.
- For small improvements, a pull request may be acceptable directly.

## Pull Request Expectations

- Keep pull requests focused and easy to review.
- Add or update tests when the change affects behavior.
- Update documentation when setup, behavior, or public APIs change.
- Call out breaking changes explicitly.
- Make sure all checks pass before requesting review.

## Commit Messages

This project uses [Conventional Commits](https://www.conventionalcommits.org/). Follow the convention consistently as
releases are generated automatically from commit messages.

### Type

* **feat**: A new feature
* **fix**: A bug fix
* **docs**: Documentation only changes
* **style**: Changes that do not affect the meaning of the code
* **refactor**: A code change that neither fixes a bug nor adds a feature
* **perf**: A code change that improves performance
* **test**: Adding missing tests or correcting existing tests
* **build**: Changes that affect the build system or CI
* **chore**: Other changes that don't modify `src` or `test` files

## Review Process

- Maintainers may request changes, clarification, tests, or documentation updates before merge.
- Approval does not guarantee merge if new risks are discovered later in review.
- Rebase or merge from the default branch when requested by maintainers.
