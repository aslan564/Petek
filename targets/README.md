# Target profiles

One file per site you test: `targets/<name>.yaml`. `PETEK_TARGET=<name>` selects a profile, and a run or an exploration
of its `url` uses its settings (test API, token reference, production hosts, mail, sign-in order, accounts). Secrets
are `${VARIABLE}` references to `.env`, never values. The panel writes the accounts you add under "Hesablar" here.

Start from [docs/examples/target-profile.yaml](../docs/examples/target-profile.yaml).
