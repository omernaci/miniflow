# Contributing to MiniFlow

Thanks for your interest in MiniFlow! Bug reports, ideas, documentation and code are all welcome.

## Reporting issues

- Search [existing issues](https://github.com/omernaci/miniflow/issues) first.
- Use the issue templates: **Bug report**, **Feature request** or **Improvement**.
- For a bug, include the MiniFlow version, how you run it (jar, Docker, IDE), the workflow JSON (without secrets)
  and the error from the Executions page or the log.
- Do not report security vulnerabilities in public issues; see [SECURITY.md](SECURITY.md).

## Submitting code

1. Fork the repository and create a branch from `main`, e.g. `feature/teams-node` or `fix/editor-form-refresh`.
2. Make your change following the [development guide](docs/en/contribute/development.md):
   feature packages under `com.omernaci.miniflow`, English code and log messages, user-facing text in the message files.
3. Add or update tests and run `./mvnw verify`.
4. Update the documentation in `docs/en` (and `docs/tr` if you can) when behaviour changes.
5. Commit with a message like `[Node] Add Microsoft Teams node` and open a pull request using the template.

Pull requests need a green build and a review before they are merged.

## Good first contributions

- New nodes (Microsoft Teams, Discord, Telegram, Notion, S3/MinIO, PDF, …)
- New credential types or languages
- Documentation fixes and new example workflows in `examples/`

## Code of conduct

This project follows the [Contributor Covenant](CODE_OF_CONDUCT.md). By participating you agree to uphold it.
