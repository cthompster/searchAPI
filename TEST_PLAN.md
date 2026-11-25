# Test plan: iTunes Search API

## 1. Scope and objectives

Functional testing of the search endpoint documented in [API.md](API.md):

`GET https://itunes.apple.com/search?term=…&country=…&media=…&limit=…`

Objectives:
- Each documented parameter (`term`, `country`, `media`, `limit`) changes the results as described.
- Defaults (`country=US`, `media=all`, `limit=50`) apply when a parameter is left out.
- Invalid input is handled predictably (rejected with an error, or ignored).
- Every response has the same, parseable format.
- Record where the live API differs from the documentation (section 6).

## 2. Approach

- **Black-box functional tests** against the live production endpoint; there is no test environment.
- **Expected results follow the live behaviour** (checked on 2026-11-25). Where it differs from
  API.md, the test asserts what the API really does and the difference is logged in section 6, so the
  suite stays green and a change in behaviour shows up as a failure.
- **Live data changes**, so tests never assert specific titles or total catalogue counts. They check
  properties that hold for any data: result types, store links, currency, counts set by `limit`, and
  that equivalent requests return the same results. Repeating an identical request returns the same
  list in the same order (checked live), so those comparisons are exact. The term and
  unknown-parameter comparisons (TC-T02, TC-T03, TC-X02) compare songs only, so a music video mixed
  into one list can't make them fail. TC-M06 compares every result of two *different* requests (no
  `media` vs `media=all`), whose mixed-media ranking can shift between the two calls (seen live: one
  result dropped and another added), so it requires almost the same results rather than the same list:
  at most 10%, and at least 2, of 10 may differ. A different default (e.g. `media=music`) shares
  none of them (checked live).
- Equivalence partitioning and boundary values for `limit` (0, 1, 200, 201, negative, non-numeric)
  and for codes (`GB`/`gb`, 2-letter vs 3-letter, unknown).

## 3. Environment and tools

| Item | Value |
|---|---|
| Language / build | Java 17, Maven |
| Test framework | JUnit 5 (Jupiter, parameterized tests) |
| HTTP client / assertions | Rest Assured 6 (with Hamcrest) |
| Endpoint | `https://itunes.apple.com` (override: `-DbaseUri=…`) |
| Rate limit | Apple allows about 20 requests a minute. The suite waits 3 s between requests (`-DrequestDelayMs=…`). |
| Timeouts | 30 s to connect and to read each response (`-DhttpTimeoutMs=…`); any test is failed after 5 minutes (`src/test/resources/junit-platform.properties`) |

How to run: see [README.md](README.md#how-to-run).

## 4. Common checks (every request)

Applied by `ITunesApi.searchOk` / `ITunesApi.searchRejected` (`src/test/java/itunes/support/ITunesApi.java`):

| Response | Checks |
|---|---|
| Successful search | HTTP 200; content type `text/javascript`; JSON body with `resultCount` and `results`; `resultCount` equals the number of items in `results` |
| Rejected search | HTTP 400; content type `text/javascript`; `errorMessage` = `Invalid value(s) for key(s): [<key>]`; `queryParameters` help object present |

When one of these checks fails, the full request and response are logged. Other assertions report
their own message.

## 5. Test cases

All cases are automated. "Method" is in `src/test/java/itunes/`.

### term

| ID | Input | Expected | Method |
|---|---|---|---|
| TC-T01 | `term=jack johnson&media=music&limit=5` | Results returned; every `artistName` contains "Jack Johnson" | `TermSearchTest.knownTermReturnsMatchingArtist` |
| TC-T02 | `term=jack+johnson` vs `term=jack%20johnson` (sent as written) | Same songs in the same order | `TermSearchTest.plusAndPercent20AreEquivalent` |
| TC-T03 | `term=JACK JOHNSON` vs `term=jack johnson` | Same songs in the same order (case-insensitive) | `TermSearchTest.termIsCaseInsensitive` |
| TC-T04 | `term=AC%2FDC`, `term=Beyonc%C3%A9` (sent as written, not re-encoded) | Decoded: a result by AC/DC, Beyoncé | `TermSearchTest.encodedCharactersFindArtist` |
| TC-T05 | No `term` | 200, `resultCount` 0 | `TermSearchTest.missingTermReturnsNoResults` |
| TC-T06 | `term=` | 200, `resultCount` 0 | `TermSearchTest.emptyTermReturnsNoResults` |
| TC-T07 | Term that matches nothing | 200, `resultCount` 0 | `TermSearchTest.unmatchedTermReturnsNoResults` |

### media

| ID | Input | Expected | Method |
|---|---|---|---|
| TC-M01 | `media=` podcast, music, musicVideo, tvShow, software, ebook, audiobook | Results, all of the matching type (`kind`, or `wrapperType` for audiobooks): podcast; song or music-video; music-video; tv-episode; software or mac-software; ebook; audiobook | `MediaTypeTest.mediaTypeFiltersResults` |
| TC-M02 | `media=all` | Results returned | `MediaTypeTest.mediaAllReturnsResults` |
| TC-M03 | `media=movie`, `media=shortFilm` | Accepted (200), `resultCount` 0 | `MediaTypeTest.emptyMediaTypesAreAccepted` |
| TC-M04 | `media=bogus`, `media=MUSIC` | 400, error names `mediaType` | `MediaTypeTest.invalidMediaIsRejected` |
| TC-M05 | `term=love&media=music&limit=200` | Both songs and music videos | `MediaTypeTest.musicIncludesMusicVideos` |
| TC-M06 | `term=love&limit=10` with no `media` vs `media=all` | Almost the same results: all but at most 2 of 10 shared (default is `all`) | `MediaTypeTest.defaultMediaIsAll` |

### limit

Counts are checked within 10% (at least ±2) except `limit=1`; see D-03.

| ID | Input | Expected | Method |
|---|---|---|---|
| TC-L01 | `limit=1` (two terms) | Exactly 1 result | `LimitTest.limitOneIsExact` |
| TC-L02 | `limit=10`, `limit=25` | About that many results | `LimitTest.limitWithinRange` |
| TC-L03 | No `limit` | About 50 results (documented default) | `LimitTest.defaultLimitIsAbout50` |
| TC-L04 | `limit=200` (documented maximum) | About 200 results | `LimitTest.maximumLimit` |
| TC-L05 | `limit=201`, `limit=500` | Not rejected; about 200 results (capped, not the default 50) | `LimitTest.limitAboveMaximumIsCapped` |
| TC-L06 | `limit=0`, `-1`, `abc` | Not rejected; results returned | `LimitTest.invalidLimitIsIgnored` |

### country

| ID | Input | Expected | Method |
|---|---|---|---|
| TC-C01 | No `country` | US store: links contain `/us/`, currency USD | `CountryTest.defaultCountryIsUs` |
| TC-C02 | `country=GB`, `country=gb` | GB store: links contain `/gb/`, currency GBP | `CountryTest.countrySelectsStore` |
| TC-C03 | `country=ZZ`, `country=USA` | 400, error names `country` | `CountryTest.invalidCountryIsRejected` |

### Combined parameters and response format

| ID | Input | Expected | Method |
|---|---|---|---|
| TC-X01 | `term=love&country=GB&media=software&limit=5` | About 5 results, all software, GBP, GB store links | `CombinedParamsTest.allParametersApply` |
| TC-X02 | Same search with and without `foo=bar` | Same songs in the same order (unknown parameter ignored) | `CombinedParamsTest.unknownParameterIsIgnored` |
| TC-R01 | Song search | Results returned; each has `wrapperType`, `kind`, `trackId`, `artistName`, `trackName`, `trackViewUrl`, `currency` | `ResponseFormatTest.songResultsHaveCoreFields` |
| TC-R02 | Any search | `Content-Disposition: attachment; filename=1.txt` | `ResponseFormatTest.resultsAreSentAsAttachment` |
| TC-R03 | `country=ZZ` | `queryParameters` keys are exactly `term`, `country`, `limit`, `lang`, `output`, `callback` | `ResponseFormatTest.errorHelpListsParameters` |

## 6. Differences between API.md and the live API

Found while writing these tests (2026-11-25). Each would be raised as a defect against either the
API or its documentation.

| ID | API.md says | Live API does | Covered by |
|---|---|---|---|
| D-01 | `limit` is 1 to 200 | 201 or more is accepted; `limit=200`, `201` and `500` return 201–207 results | TC-L04, TC-L05 |
| D-02 | `limit` is 1 to 200 | 0, negative and non-numeric values aren't rejected (0 and -1 returned 155 results, `abc` 51) | TC-L06 |
| D-03 | `limit` is the number of results returned | Exact when one kind of result comes back. When kinds are mixed, a few more or fewer, depending on the search: default 54 instead of 50, 26 or 23 for `limit=25` (different terms and media), 6 or 4 for `limit=5`. Repeating the same request gives the same count. | TC-L02, TC-L03, TC-L04, TC-L05 |
| D-04 | `term` is required | A missing or empty `term` returns 200 with no results, not an error | TC-T05, TC-T06 |
| D-05 | `movie` and `shortFilm` are media types | Accepted but always return 0 results | TC-M03 |
| D-06 | (not stated) | `media=music` also returns music videos | TC-M05 |
| D-07 | (not stated) | `media` is case-sensitive (`MUSIC` is rejected); `country` is not (`gb` works) | TC-M04, TC-C02 |
| D-08 | (no error format documented) | Invalid values return 400 with `errorMessage` and a `queryParameters` help object; the error calls `media` "`mediaType`", and the help object lists `lang`, `output` and `callback` but not `media` | TC-M04, TC-C03, TC-R03 |
| D-09 | (not stated) | JSON is served as `text/javascript`, not `application/json`, with `Content-Disposition: attachment; filename=1.txt` | Content type: all (common checks); header: TC-R02 |

## 7. Out of scope

- Load, stress and performance testing: this is Apple's production service, and its rate limit
  forbids it.
- Security and attack testing (injection, fuzzing) against a third-party service.
- Parameters API.md doesn't document (`entity`, `attribute`, `lang`, `callback`, `explicit`, ...).
- The separate Lookup API (`/lookup`).

## 8. Risks

| Risk | Mitigation |
|---|---|
| Catalogue data changes (titles, counts) | Assertions check properties, not specific data; well-known artists used as search terms |
| Behaviour changes (any of D-01 to D-09 fixed) | Intended: every difference is asserted, so a fix makes a test fail; update the test and section 6 |
| Throttling (403/429) | 3 s between requests; if it happens, wait a few minutes before re-running |
| The API hangs | 30 s connect/read timeout per request, 5-minute limit per test |
| Network or regional differences | Store-specific tests set `country` explicitly |
