# Verification policy

The Verify GitHub Actions workflow runs on pull requests targeting `main` and on
manual `workflow_dispatch`. Ordinary pushes, including direct development pushes
to `main`, do not trigger it. PR branch updates still trigger the PR check.

Python tests/static checks, Android JVM/debug lint, and tracked-HEAD secret scanning
remain intact. Run verification before merging a PR; optionally run `make verify`
locally during development. This policy changes scheduling, not test results.

To make successful checks mandatory for merge, configure GitHub branch protection
or a ruleset for `main` requiring these jobs. That repository setting is not changed
by this commit and was not accessible with the available GitHub permissions. A
manually dispatched workflow alone is not a substitute for PR-required checks.

## Observed remote failure

Latest inspected run: https://github.com/triskill/seed/actions/runs/37214876560
(commit ccccb67). Python failed at Test both suites; Android failed at
android-actions/setup-android; the secret scan passed. Detailed log download was
rejected with HTTP 403, so exact root causes are not established here. Moving the
workflow to PR/manual triggers does not fix those failures. Local verification and
phone acceptance recorded in the implementation reports are separate evidence.
