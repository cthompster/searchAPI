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

Requires Java 17 and Maven 3.9+.

| Command | What it does |
|---|---|
| `mvn test` | All tests, about 2 minutes (requests are spaced 3 s apart to respect Apple's rate limit) |
| `mvn test -Dtest=LimitTest` | One class |
| `mvn test -Dtest='LimitTest#limitOneIsExact'` | One test |
| `mvn -q test-compile` | Compile only (warnings fail the build) |
| `mvn test -DrequestDelayMs=5000` | Slower requests, if the API starts throttling |

Results are in `target/surefire-reports/`. A failed check logs the full request and response.
