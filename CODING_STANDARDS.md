# Coding Standards

## General Principles

- Prefer simple, maintainable solutions over clever ones.
- Optimize for readability and safe change review.
- Keep public behavior explicit and documented.
- Avoid introducing dependencies without a clear maintenance benefit.

## Required for Every Change

- Tests must cover bug fixes and non-trivial features where automated testing is practical.
- User-visible behavior changes must be documented in the relevant README, docs, or changelog entry.
- Breaking changes must be called out explicitly in pull requests and release notes.

## Code Style

- Follow the IntelliJ default Java formatter. Format code before committing.
- Use `camelCase` for methods and variables, `UpperCamelCase` for classes, `ALL_UPPER_CASE` for static constants.
- Keep functions focused. If a function does multiple unrelated things, split it.
- Remove dead code rather than commenting it out.
- Keep comments rare and useful. Explain intent or non-obvious tradeoffs, not obvious syntax.

## Dependencies

- Prefer standard library or existing project dependencies before adding new packages.
- New dependencies must be actively maintained, appropriately licensed, and justified in the pull request.

## Documentation

- Document only non-obvious public members using Javadoc.
- Keep setup instructions in the README accurate.

## Testing

- Use JUnit 5 for testing.
- Follow naming conventions with `Test` postfix for unit tests.
- Use descriptive names for test cases (e.g., `shouldCalculateDelay`, `shouldThrowOnInvalidInput`).

## Security

- Do not hardcode secrets, credentials, or tokens.
- Apply the principle of least privilege to workflows, bots, and integrations.
- Review third-party GitHub Actions and pin them to trusted versions.
