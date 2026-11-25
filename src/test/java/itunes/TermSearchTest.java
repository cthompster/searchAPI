package itunes;

import static itunes.support.ITunesApi.checkOk;
import static itunes.support.ITunesApi.lower;
import static itunes.support.ITunesApi.results;
import static itunes.support.ITunesApi.search;
import static itunes.support.ITunesApi.searchOk;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.restassured.response.Response;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("term parameter")
class TermSearchTest {

    @Test
    @DisplayName("TC-T01 a known artist returns only that artist's music")
    void knownTermReturnsMatchingArtist() {
        List<Map<String, Object>> results =
                results(searchOk(Map.of("term", "jack johnson", "media", "music", "limit", 5)));

        assertFalse(results.isEmpty(), "expected results for 'jack johnson'");
        assertAll(results.stream().map(r -> () -> assertTrue(
                lower(r.get("artistName")).contains("jack johnson"),
                "artistName was " + r.get("artistName"))));
    }

    @Test
    @DisplayName("TC-T02 '+' and '%20' encodings of a space give the same results")
    void plusAndPercent20AreEquivalent() {
        // Encoding is turned off so the API receives the literal '+' or '%20'.
        Response plus = checkOk(search().urlEncodingEnabled(false)
                .queryParam("term", "jack+johnson").queryParam("media", "music").queryParam("limit", 10)
                .when().get());
        Response percent20 = checkOk(search().urlEncodingEnabled(false)
                .queryParam("term", "jack%20johnson").queryParam("media", "music").queryParam("limit", 10)
                .when().get());

        List<Long> plusIds = plus.jsonPath().getList("results.trackId", Long.class);
        assertFalse(plusIds.isEmpty(), "expected results for 'jack+johnson'");
        assertEquals(plusIds, percent20.jsonPath().getList("results.trackId", Long.class));
    }

    @Test
    @DisplayName("TC-T03 the term is case-insensitive")
    void termIsCaseInsensitive() {
        List<Long> lowerIds = searchOk(Map.of("term", "jack johnson", "media", "music", "limit", 10))
                .jsonPath().getList("results.trackId", Long.class);
        List<Long> upperIds = searchOk(Map.of("term", "JACK JOHNSON", "media", "music", "limit", 10))
                .jsonPath().getList("results.trackId", Long.class);

        assertFalse(lowerIds.isEmpty(), "expected results for 'jack johnson'");
        assertEquals(lowerIds, upperIds);
    }

    @ParameterizedTest(name = "TC-T04 special characters: {0}")
    @ValueSource(strings = {"AC/DC", "Beyoncé"})
    @DisplayName("TC-T04 terms with characters that must be URL-encoded find the artist")
    void encodedCharactersFindArtist(String artist) {
        List<Map<String, Object>> results =
                results(searchOk(Map.of("term", artist, "media", "music", "limit", 5)));

        assertTrue(results.stream().anyMatch(r -> lower(r.get("artistName")).contains(lower(artist))),
                "no result by " + artist);
    }

    @Test
    @DisplayName("TC-T05 no term returns an empty result list, not an error")
    void missingTermReturnsNoResults() {
        assertEquals(0, searchOk(Map.of("media", "music")).jsonPath().getInt("resultCount"));
    }

    @Test
    @DisplayName("TC-T06 an empty term returns an empty result list")
    void emptyTermReturnsNoResults() {
        assertEquals(0, searchOk(Map.of("term", "")).jsonPath().getInt("resultCount"));
    }

    @Test
    @DisplayName("TC-T07 a term that matches nothing returns an empty result list")
    void unmatchedTermReturnsNoResults() {
        assertEquals(0, searchOk(Map.of("term", "zzqxqzzqxq9876")).jsonPath().getInt("resultCount"));
    }
}
