package itunes;

import static itunes.support.ITunesApi.checkOk;
import static itunes.support.ITunesApi.lower;
import static itunes.support.ITunesApi.results;
import static itunes.support.ITunesApi.search;
import static itunes.support.ITunesApi.searchOk;
import static itunes.support.ITunesApi.songIds;
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
import org.junit.jupiter.params.provider.CsvSource;

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

        // Identical requests return the same list each time (checked live), so the lists must match.
        List<Long> plusIds = songIds(plus);
        assertFalse(plusIds.isEmpty(), "expected songs for 'jack+johnson'");
        assertEquals(plusIds, songIds(percent20));
    }

    @Test
    @DisplayName("TC-T03 the term is case-insensitive")
    void termIsCaseInsensitive() {
        List<Long> lowerIds = songIds(searchOk(Map.of("term", "jack johnson", "media", "music", "limit", 10)));
        List<Long> upperIds = songIds(searchOk(Map.of("term", "JACK JOHNSON", "media", "music", "limit", 10)));

        assertFalse(lowerIds.isEmpty(), "expected songs for 'jack johnson'");
        assertEquals(lowerIds, upperIds);
    }

    /**
     * Sends the term already percent-encoded, with Rest Assured's own encoding turned off, so the test
     * proves the API decodes {@code %2F} ("/") and UTF-8 sequences ("é") rather than relying on how
     * Rest Assured would encode them.
     */
    @ParameterizedTest(name = "TC-T04 term={0} finds {1}")
    @CsvSource({
            "AC%2FDC,      AC/DC",
            "Beyonc%C3%A9, Beyoncé",
    })
    @DisplayName("TC-T04 percent-encoded special characters are decoded and find the artist")
    void encodedCharactersFindArtist(String encodedTerm, String artist) {
        List<Map<String, Object>> results = results(checkOk(search().urlEncodingEnabled(false)
                .queryParam("term", encodedTerm).queryParam("media", "music").queryParam("limit", 5)
                .when().get()));

        assertFalse(results.isEmpty(), "expected results for " + encodedTerm);
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
