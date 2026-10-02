# Target profiles

One file per site you test: `targets/<name>.yaml`. `PETEK_TARGET=<name>` selects a profile, and a run or an exploration
of its `url` uses its settings (test API, token reference, production hosts, mail, sign-in order, accounts). Secrets
are `${VARIABLE}` references to `.env`, never values. The panel writes the sites you add under "Saytlar" and the
accounts you add under "Hesablar" here (their tokens and passwords go to `.env`), and uses them without a restart.

Start from [docs/examples/target-profile.yaml](../docs/examples/target-profile.yaml).
