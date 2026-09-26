## Pətək (multi-agent AI testing of this project's site)

Pətək runs next to this project as a sidecar (never inside its build) and tests the site with many AI tester agents
at once, each in its own browser session; results are evidence-based reports. Before using it, read `.petek/SKILL.md`
in this repository: it names the roles you may take (explorer, scenario author, judge, root-cause), the commands and
MCP tools, and the rules (assertions are checked by code, never by you; time is measured by the harness; secrets stay
out of prompts and logs; production hosts need `PETEK_ALLOW_PRODUCTION=true`).

- Configuration: `.env` (`PETEK_TARGET` is the site under test). Never commit it.
- Check: `petek doctor` · Panel: `petek panel` (http://127.0.0.1:7070) · Scenarios: `scenarios/*.yaml`.
- Evidence and reports: `evidence/` (git-ignored). A finding's screenshots, requests and oracle answers are there.
- MCP: the `petek` server (`petek mcp`, stdio) exposes the same use cases as the panel.
