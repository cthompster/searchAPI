package itunes;

import static itunes.support.ITunesApi.results;
import static itunes.support.ITunesApi.searchOk;
import static itunes.support.ITunesApi.searchRejected;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The response contract itself (HTTP 200, {@code text/javascript} JSON, resultCount equal to the
 * number of results) is checked by {@code ITunesApi.searchOk} on every successful search; the
 * error contract by {@code ITunesApi.searchRejected}. This class adds the per-result fields and the
 * undocumented details listed in TEST_PLAN.md (D-08, D-09).
 */
@DisplayName("response format")
class ResponseFormatTest {

    @Test
    @DisplayName("TC-R01 each song has the fields a client needs to show and link it")
    void songResultsHaveCoreFields() {
        List<Map<String, Object>> results =
                results(searchOk(Map.of("term", "jack johnson", "media", "music", "limit", 5)));

        assertFalse(results.isEmpty(), "expected results");
        assertAll(results.stream().flatMap(r -> List.of(
                "wrapperType", "kind", "trackId", "artistName", "trackName", "trackViewUrl", "currency")
                .stream().map(field -> () -> assertNotNull(r.get(field), "missing " + field + " in " + r))));
    }

    @Test
    @DisplayName("TC-R02 results are sent as a file download named 1.txt")
    void resultsAreSentAsAttachment() {
        assertEquals("attachment; filename=1.txt",
                searchOk(Map.of("term", "jack johnson", "limit", 1)).header("Content-Disposition"));
    }

    @Test
    @DisplayName("TC-R03 an error's help object lists the accepted parameters, without media")
    void errorHelpListsParameters() {
        Map<String, Object> help = searchRejected(Map.of("term", "jack johnson", "country", "ZZ"), "country")
                .jsonPath().getMap("queryParameters");

        assertEquals(Set.of("term", "country", "limit", "lang", "output", "callback"), help.keySet());
    }
}
