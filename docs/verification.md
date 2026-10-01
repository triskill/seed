# Verification

From the repository root, create a Python 3.12 virtualenv and install the editable dev packages:

```sh
python -m venv .venv
.venv/bin/python -m pip install -e './backend[dev]' -e './webapp[dev]'
make verify-python verify-python-static
```

With JDK 17 and Android SDK platform 34/build-tools 34.0.0 installed, run `make verify-android`, or `make verify` for all three checks. The Android target executes `:app:testDebugUnitTest :app:lintDebug` without an emulator, connected device, generated runtime, or instrumented tests. CI runs the Python, Android and secret-scanning jobs independently.

Static checks are **incremental**: Ruff checks E4/E7/E9/F821/F823 on production Python only; mypy checks four named modules with `--follow-imports=skip --ignore-missing-imports`. This is not project-wide lint/type coverage. Baseline full-scope Ruff E4/E7/E9/F across production and tests found 43 diagnostics; baseline mypy across both production packages found 25 errors in five files. Expand scope after addressing these, rather than ignoring or baselining them.

## Secret scanning policy

CI downloads gitleaks v8.24.3 and checks its Linux x64 archive against a pinned SHA-256 before scanning a `git archive HEAD` snapshot. Findings and diagnostic details are suppressed in public CI logs; failures still fail the job. This scans tracked files at checked-out HEAD, including changes in a pull-request merge commit, but **does not scan prior commits, untracked files, or the PR commit history**. The repository has an owner-decided historical revoked token: no history baseline or exception fingerprint is committed because generating/distributing one could expose the token. The owner reports the historical token revoked and decided to leave history intact. Full-history scanning would need a privately reviewed, narrowly scoped exception for that known finding; do not add a broad allowlist or print the token. `.pi/agent/.gitignore` excludes `auth.json`; never add an auth file or scanner report containing a credential to git.

To run the same snapshot scan privately, use the verified gitleaks v8.24.3 binary and extract `git archive HEAD` into a temporary directory outside the repository, then run `gitleaks dir <directory> --redact=100 --no-banner --log-level error` without sharing raw findings. The CI scan does not replace review of new commits or a local scan of uncommitted files.
