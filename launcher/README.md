# petek (launcher)

The npm launcher of [Pətək](https://github.com/aslan564/Petek), the multi-agent AI test platform: many AI tester
agents test a web application at once, each in its own browser session, and report with evidence.

```bash
npx petek init --target https://staging.your-site.com   # .env, .petek/, skill pack and MCP entry for your AI agent
npx petek doctor                                         # target policy, target, Chromium, inbox, test API, AI provider
npx petek panel                                          # the web panel at http://127.0.0.1:7070
```

The first run downloads the release bundle for this machine (Linux x64/arm64, macOS Apple silicon, Windows x64;
each carries its own Java runtime, so no JDK is needed) into `~/.petek/versions/<version>`, checks its SHA-256
against the release's `SHA256SUMS`, and every run after that starts it directly. The launcher itself has no
dependencies. Chromium is downloaded by Playwright on first use. You need an AI, any one: an AI command-line tool you
are logged in to, or an API key for any OpenAI-compatible service (see `.env.example`).

| Variable | Meaning |
|---|---|
| `PETEK_VERSION` | run another release than the launcher's own version |
| `PETEK_DOWNLOAD_BASE` | a mirror of the release assets (`<base>/petek-<version>-<platform>.tar.gz` and `<base>/SHA256SUMS`) |
| `PETEK_HOME` | where versions live (default `~/.petek`) |

Intel Macs: no bundle is built yet; download `petek-<version>-any-jdk25.zip` from the releases page (needs JDK 25).

Licence: Apache License 2.0 (see `LICENSE`). Documentation, configuration
keys and the target contract: the [repository](https://github.com/aslan564/Petek).
