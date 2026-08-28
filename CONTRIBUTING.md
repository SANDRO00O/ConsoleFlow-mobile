# Contributing to ConsoleFlow

First off, thanks for taking the time to contribute! 🎉 ConsoleFlow moved to the ConsoleFlow-Group organization specifically to make outside contributions easier, and every bit of help — code, bug reports, documentation, or testing on a device we don't have — is genuinely appreciated.

## Ways to contribute

- **Report a bug** — open a [bug report](https://github.com/ConsoleFlow-Group/ConsoleFlow-mobile/issues/new/choose). Include your device type (phone, tablet, Android TV, Fire TV), Android version, and steps to reproduce.
- **Suggest a feature** — open a [feature request](https://github.com/ConsoleFlow-Group/ConsoleFlow-mobile/issues/new/choose) describing the problem it solves.
- **Fix something or build a feature** — see [Your first pull request](#your-first-pull-request) below.
- **Test on hardware we don't have** — TV boxes, controllers, and remotes vary a lot. A comment on GitHub about what does or doesn't work is genuinely useful, even without any code.

## Project setup

See the [Getting Started](README.md#getting-started) section of the README for build prerequisites and commands. In short: JDK 17 + the Android SDK, then `./gradlew assembleDebug`.

## Your first pull request

1. Fork the repository and clone your fork.
2. Create a branch off `main`: `git checkout -b feature/short-description` (or `fix/short-description`).
3. Make your changes.
   - Match the existing Kotlin style already in the file you're editing — see [Code style](#code-style) below.
   - Keep commits focused; one logical change per commit is much easier to review than one giant commit.
4. Build and manually test with `./gradlew assembleDebug` before opening a PR. There's no automated test suite yet, so a quick manual pass — and a note in the PR description of what you tested — matters a lot.
5. Push your branch and open a pull request against `main`. Fill in the PR template; it's short, but it helps reviewers a lot.
6. The **Quick APK** GitHub Action automatically builds your PR so a maintainer can review the code and try the build.

## Code style

- Kotlin, following the project's existing conventions (`kotlin.code.style=official`, set in `gradle.properties`).
- 4-space indentation. The repo includes an [`.editorconfig`](.editorconfig) that most editors and IDEs pick up automatically.
- The codebase is organized by feature package (`tabs/`, `web/`, `download/`, `storage/`, `console/`, `cache/`, `ui/`) — new code should follow that layout rather than growing `MainActivity.kt` further.
- No hard rule on comments, but non-obvious platform quirks — WebView, TV, or controller-input behavior especially — are worth a short explanation for the next person. (You'll notice a couple of existing files have doc comments in Arabic; English is completely fine for new comments.)

## Commit messages

A short, imperative summary line works best — e.g. `Fix tab group rename dialog crash on TV`, not `fixed bug`. Add a body if the *why* isn't obvious from the diff.

## Reporting security issues

Please don't open a public issue for security vulnerabilities — see [SECURITY.md](SECURITY.md) instead.

## Code of Conduct

This project follows a [Code of Conduct](CODE_OF_CONDUCT.md). By participating, you agree to uphold it.

---

Thanks again — looking forward to your contribution! 🚀
