package itunes;

import static itunes.support.ITunesApi.results;
import static itunes.support.ITunesApi.searchOk;
import static itunes.support.ITunesApi.searchRejected;
import static itunes.support.ITunesApi.typeOf;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("media parameter")
class MediaTypeTest {

    /**
     * Each media value with the result types it may return: the result's {@code kind}, or its
     * {@code wrapperType} when it has no kind (audiobooks). {@code music} also returns music videos.
     */
    @ParameterizedTest(name = "TC-M01 media={0} returns only {1}")
    @CsvSource({
            "podcast,    podcast",
            "music,      song|music-video",
            "musicVideo, music-video",
            "tvShow,     tv-episode",
            "software,   software|mac-software",
            "ebook,      ebook",
            "audiobook,  audiobook",
    })
    @DisplayName("TC-M01 each media type returns only results of that type")
    void mediaTypeFiltersResults(String media, String allowedTypes) {
        Set<String> allowed = Set.of(allowedTypes.split("\\|"));
        List<Map<String, Object>> results =
                results(searchOk(Map.of("term", "love", "media", media, "limit", 5)));

        assertFalse(results.isEmpty(), "expected results for media=" + media);
        assertAll(results.stream().map(r -> () -> assertTrue(allowed.contains(typeOf(r)),
                "media=" + media + " returned a " + typeOf(r))));
    }

    @Test
    @DisplayName("TC-M02 media=all returns results")
    void mediaAllReturnsResults() {
        assertFalse(results(searchOk(Map.of("term", "love", "media", "all", "limit", 5))).isEmpty());
    }

    /** Documented values that currently return no results (see TEST_PLAN.md, D-05). */
    @ParameterizedTest(name = "TC-M03 media={0} is accepted")
    @ValueSource(strings = {"movie", "shortFilm"})
    @DisplayName("TC-M03 media values that currently return nothing are still accepted")
    void emptyMediaTypesAreAccepted(String media) {
        searchOk(Map.of("term", "love", "media", media, "limit", 5));
    }

    @ParameterizedTest(name = "TC-M04 media={0} is rejected")
    @ValueSource(strings = {"bogus", "MUSIC"})
    @DisplayName("TC-M04 unknown or wrongly-cased media values are rejected with HTTP 400")
    void invalidMediaIsRejected(String media) {
        searchRejected(Map.of("term", "jack johnson", "media", media), "mediaType");
    }
}
