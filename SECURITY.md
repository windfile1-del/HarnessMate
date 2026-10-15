# Security policy

HarnessMate is an experimental Android AI agent with tools that may affect real files, applications, network sessions and user accounts.

## Reporting a vulnerability

**Do not post exploits, tokens, private logs, passwords, screenshots containing personal data, signing keys or other sensitive details in public Issues.** Contact the repository maintainer through a private channel before public disclosure. Please include a concise reproduction, impact, affected commit, Android version and safe-to-share logs.

## Responsible use

- Grant only required filesystem, Accessibility, screen, browser and ADB capabilities.
- Treat AI output and action status as untrusted until validated.
- Keep signing keys and model credentials out of Git; do not commit local build directories or backups.
- Confirm high-impact actions (messages, deletion, installation, account changes, purchase, publication).
- Local ADB and virtual displays depend on Android security policies and are not Root or guaranteed isolation.
- Third-party model providers and websites have their own data policies and terms.
- This repository is source-only; **do not obtain missing native toolchains from untrusted distribution sites**.

## Support status

This is an evolving open-source source snapshot, not a security-certified product or a guarantee of compatibility with any Android device or third-party account.
