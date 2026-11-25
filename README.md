# searchAPI

*    Write a test plan for the documented [iTunes Search API](https://github.com/iappsqainterview/searchAPI/blob/master/API.md)
  

*    Write Java automation that will make requests to this API:<br>
     Include TestNG or JUnit classes with validations for the documented functional test cases

## Solution

- **Test plan:** [TEST_PLAN.md](TEST_PLAN.md): scope, approach, every test case with the method that
  automates it, and the differences found between [API.md](API.md) and the live API.
- **Automation:** JUnit 5 + Rest Assured tests in `src/test/java/itunes/`, one class per parameter
  (`TermSearchTest`, `MediaTypeTest`, `LimitTest`, `CountryTest`) plus `CombinedParamsTest` and
  `ResponseFormatTest`. Shared request setup and response checks are in `itunes/support/ITunesApi.java`.

The tests call the live API, so they need internet access.

## How to run

Requires Java 17 and Maven 3.9+ (the build checks both).

| Command | What it does |
|---|---|
| `mvn test` | All tests, about 2 minutes (requests are spaced 3 s apart to respect Apple's rate limit) |
| `mvn test -Dtest=LimitTest` | One class |
| `mvn test -Dtest='LimitTest#limitOneIsExact'` | One test |
| `mvn -q test-compile` | Compile only (warnings fail the build) |
| `mvn test -DrequestDelayMs=5000` | Slower requests, if the API starts throttling |
| `mvn test -DhttpTimeoutMs=60000` | Longer connect/read timeout per request (default 30 s) |

### Test run history

Every run (Maven or the IDE) is also saved as a numbered HTML report, so you can go back through the
first, second, third … and latest runs. Open `test-history/index.html` in a browser: it lists every kept
run with its counts, pass rate and duration, plus a pass/fail trend; click a run to see each test's
result, time and, for a failure, the message and stack trace.

- Files: `test-history/run-NNNN_<date>_<time>.html`, `index.html`, and `runs.tsv` (the list the index
  is built from).
- A run is marked `filtered` (and its file gets `_filtered`) only when Maven's `-Dtest=…` chose the
  tests. Running one class or test from the IDE, or `-Dgroups`, is recorded as a full run; the trend's
  shorter bars show such partial runs.
- Results are passed, failed, skipped (`@Disabled`) or aborted (a failed assumption; like Surefire, not
  a failure). The pass rate counts only passed and failed tests, rounded down
  (so a run with a failure never shows 100%).
- A run is one JUnit test plan: the whole `mvn test` with this project's settings. With
  `-DforkCount=2` or more, or `-Dsurefire.rerunFailingTestsCount`, Surefire runs several plans, and each
  is saved as its own run.
- `-DhistoryKeep=N` keeps the newest N runs (default 50; `0` keeps all); `-DhistoryDir=…` moves the folder.
- The folder is git-ignored and outside `target/`, so `mvn clean` keeps it. A problem writing it, or an
  invalid setting, is printed as a warning and never fails the run.
- `RunHistoryListenerTest` checks the history offline (in a temporary folder, no API calls); it runs with
  `mvn test` and takes well under a second. Run it alone with `mvn test -Dtest=RunHistoryListenerTest`.
  Its sample test classes (nested in it) run only inside it; Maven skips them, but running the whole
  package from the IDE lists their 7 tests as skipped.

The latest run's raw results are in `target/surefire-reports/`. When a shared response check fails (status, content type,
`resultCount`, error body), the full request and response are logged; other failed assertions show
their own message.
