# CLAUDE.md

Guidance for Claude Code in this repository. Human-facing docs: `README.md` (how to run, run history),
`TEST_PLAN.md` (every test case and the differences between `API.md` and the live API) and `API.md`
(the API as documented).

## What this is

A Java 17 + Maven suite of JUnit 5 and Rest Assured tests for the **live** iTunes Search API
(`https://itunes.apple.com/search`). All code is test code under `src/test/java/`; there is no `src/main`.

- `itunes/*Test.java`: one class per parameter, plus combined parameters and response format (see README).
- `itunes/support/ITunesApi.java`: shared request setup and checks. Use `searchOk(params)` (200 plus the
  common checks), `searchRejected(params, key)` (400 naming the key), `results`, `songIds`, `typeOf`,
  `storeUrlOf`, `lower` and `limitTolerance` rather than repeating them. `ThrottleFilter` spaces requests.
- `itunes/reporting/RunHistoryListener.java`: saves every run to `test-history/` (see README). Registered
  in `src/test/resources/META-INF/services/`; `RunHistoryListenerTest` tests it offline.

## Running the tests

All commands run from the repo root. Details and the `-D` settings are in README "How to run" (and
`-DbaseUri` in TEST_PLAN section 3).

| Command | What it does |
|---|---|
| `mvn -q test-compile` | Compile only (`-Xlint:all -Werror`, so warnings fail the build) |
| `mvn test -Dtest=RunHistoryListenerTest` | Offline tests only (they take under a second), no API calls |
| `mvn test -Dtest=LimitTest` / `'-Dtest=LimitTest#limitOneIsExact'` | One class / one test against the live API |
| `mvn test` | Everything: 72 tests (43 live, 29 offline), about 2½ minutes |

- Expected result today: `Tests run: 72, Failures: 0, Errors: 0, Skipped: 0`.
- Every run, filtered ones included, is added to `test-history/index.html`. When trying things out, pass
  `-DhistoryDir=<a scratch folder>` so experiments don't clutter the user's history.
- `target/surefire-reports/` holds only the latest run.

## The live API: rules

- Apple throttles at about 20 requests a minute. Requests are spaced 3 s apart (`-DrequestDelayMs`);
  don't lower it, and don't run the full suite repeatedly. Prefer compiling, the offline tests or one
  class while iterating, then one full run. On HTTP 403 or 429, wait a few minutes rather than retrying.
- Plain functional calls only: no load, stress or security-attack testing.
- Check live behaviour with a single `curl` before writing an assertion about it, rather than trusting
  `API.md`; they differ in several ways (TEST_PLAN section 6, D-01 to D-09).

## Conventions

- **Assert what the live API does.** Where it differs from `API.md`, the test encodes the live behaviour
  and the difference is logged in TEST_PLAN section 6 as a new `D-NN` row.
- **Live data changes.** Never assert specific titles or total catalogue counts. Check properties that
  hold for any data (result types, store links, currency, counts set by `limit`, equivalent requests).
  Counts of mixed result kinds drift: compare them with `limitTolerance` (10%, at least 2). Two
  *different* requests can rank results slightly differently, so compare them with a tolerance too
  (see `MediaTypeTest.defaultMediaIsAll`).
- **Test IDs.** Every API test has `@DisplayName("TC-X01 …")` (parameterized tests also put the ID in
  `name`), and each ID has a row in TEST_PLAN section 5 naming the method that automates it. When a test
  is added, renamed or changed, update that row (and section 6 if a difference is involved).
- Use `Locale.ROOT` for case conversion and formatting.
- Keep this file's "Expected result" (and the README's run time) in step when tests are added or removed.

## Git workflow

The user works one change at a time and asks for each step: new branch from `master` → commit → push
and open a PR → merge with a merge commit (`gh pr merge --merge`) → switch to `master`, update it, and
delete the branch locally and on GitHub.

- Never commit to `master` directly, and don't commit, push, merge or delete until asked. After each
  step, offer the next one.
- This repo is a fork (`upstream` is `task032015/searchAPI`). Always pass `--repo cthompster/searchAPI`
  to `gh pr …`, or gh targets the upstream repo.
- PR descriptions: What / Why / Changes (a table) / Testing, with the test result.
- `test-history/`, `target/` and IDE files are git-ignored.
