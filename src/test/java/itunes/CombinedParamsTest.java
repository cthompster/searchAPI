package itunes;

import static itunes.support.ITunesApi.limitTolerance;
import static itunes.support.ITunesApi.results;
import static itunes.support.ITunesApi.searchOk;
import static itunes.support.ITunesApi.songIds;
import static itunes.support.ITunesApi.storeUrlOf;
import static itunes.support.ITunesApi.typeOf;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("parameters combined")
class CombinedParamsTest {

    @Test
    @DisplayName("TC-X01 term, country, media and limit are all applied together")
    void allParametersApply() {
        List<Map<String, Object>> results =
                results(searchOk(Map.of("term", "love", "country", "GB", "media", "software", "limit", 5)));

        assertFalse(results.isEmpty(), "expected results");
        assertTrue(Math.abs(results.size() - 5) <= limitTolerance(5), "expected about 5 results, got " + results.size());
        assertAll(results.stream().map(r -> () -> {
            assertEquals("software", typeOf(r));
            assertEquals("GBP", r.get("currency"));
            assertTrue(storeUrlOf(r).contains("/gb/"), "link not in the GB store: " + storeUrlOf(r));
        }));
    }

    @Test
    @DisplayName("TC-X02 an unknown parameter is ignored")
    void unknownParameterIsIgnored() {
        Map<String, Object> params = Map.of("term", "jack johnson", "media", "music", "limit", 5);
        List<Long> without = songIds(searchOk(params));

        Map<String, Object> withExtra = new HashMap<>(params);
        withExtra.put("foo", "bar");
        List<Long> with = songIds(searchOk(withExtra));

        assertFalse(without.isEmpty(), "expected songs");
        assertEquals(without, with);
    }
}
