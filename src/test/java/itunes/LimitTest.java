package itunes;

import static itunes.support.ITunesApi.limitTolerance;
import static itunes.support.ITunesApi.searchOk;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The live API treats {@code limit} as approximate once results of several kinds are merged, and
 * doesn't reject values outside 1–200. These tests check that behaviour; see TEST_PLAN.md (D-01 to
 * D-03) for how it differs from API.md.
 */
@DisplayName("limit parameter")
class LimitTest {

    /** A term with hundreds of songs, so every limit below can be filled. */
    private static final String TERM = "jack johnson";

    /** The documented maximum; the live API returns up to about this many whatever the limit. */
    private static final int MAX_LIMIT = 200;

    private static int count(Object limit) {
        return searchOk(Map.of("term", TERM, "media", "music", "limit", limit))
                .jsonPath().getInt("resultCount");
    }

    private static void assertAbout(int expected, int actual) {
        int tolerance = limitTolerance(expected);
        assertTrue(Math.abs(actual - expected) <= tolerance,
                "expected about " + expected + " (±" + tolerance + ") results but got " + actual);
    }

    @ParameterizedTest(name = "TC-L01 term={0}, limit=1")
    @ValueSource(strings = {"jack johnson", "love"})
    @DisplayName("TC-L01 limit=1 returns exactly one result")
    void limitOneIsExact(String term) {
        assertEquals(1, searchOk(Map.of("term", term, "media", "music", "limit", 1))
                .jsonPath().getInt("resultCount"));
    }

    @ParameterizedTest(name = "TC-L02 limit={0}")
    @ValueSource(ints = {10, 25})
    @DisplayName("TC-L02 a limit within 1–200 returns about that many results")
    void limitWithinRange(int limit) {
        assertAbout(limit, count(limit));
    }

    @Test
    @DisplayName("TC-L03 without a limit, about 50 results are returned")
    void defaultLimitIsAbout50() {
        assertAbout(50, searchOk(Map.of("term", TERM, "media", "music")).jsonPath().getInt("resultCount"));
    }

    @Test
    @DisplayName("TC-L04 limit=200 (the documented maximum) returns about 200 results")
    void maximumLimit() {
        assertAbout(200, count(200));
    }

    @ParameterizedTest(name = "TC-L05 limit={0}")
    @ValueSource(ints = {201, 500})
    @DisplayName("TC-L05 a limit above 200 isn't rejected; the count stays near the maximum")
    void limitAboveMaximumIsCapped(int limit) {
        int count = count(limit);
        assertTrue(count > 0, "expected results for limit=" + limit);
        assertTrue(count <= MAX_LIMIT + limitTolerance(MAX_LIMIT),
                "limit=" + limit + " returned " + count + " results, more than the maximum");
    }

    @ParameterizedTest(name = "TC-L06 limit={0}")
    @ValueSource(strings = {"0", "-1", "abc"})
    @DisplayName("TC-L06 an invalid limit isn't rejected; results are still returned")
    void invalidLimitIsIgnored(String limit) {
        assertTrue(count(limit) > 0, "expected results for limit=" + limit);
    }
}
