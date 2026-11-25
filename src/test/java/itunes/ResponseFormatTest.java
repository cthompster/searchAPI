package itunes;

import static itunes.support.ITunesApi.results;
import static itunes.support.ITunesApi.searchOk;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The response contract itself (HTTP 200, {@code text/javascript} JSON, resultCount equal to the
 * number of results) is checked by {@code ITunesApi.searchOk} on every successful search; the
 * error contract by {@code ITunesApi.searchRejected}. This class adds the per-result fields.
 */
@DisplayName("response format")
class ResponseFormatTest {

    @Test
    @DisplayName("TC-R01 each song has the fields a client needs to show and link it")
    void songResultsHaveCoreFields() {
        List<Map<String, Object>> results =
                results(searchOk(Map.of("term", "jack johnson", "media", "music", "limit", 5)));

        assertAll(results.stream().flatMap(r -> List.of(
                "wrapperType", "kind", "trackId", "artistName", "trackName", "trackViewUrl", "currency")
                .stream().map(field -> () -> assertNotNull(r.get(field), "missing " + field + " in " + r))));
    }
}
