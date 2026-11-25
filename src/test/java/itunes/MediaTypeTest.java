package itunes;

import static itunes.support.ITunesApi.results;
import static itunes.support.ITunesApi.searchOk;
import static itunes.support.ITunesApi.searchRejected;
import static itunes.support.ITunesApi.typeOf;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import itunes.support.ITunesApi;
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

    /**
     * Documented values that currently return no results (see TEST_PLAN.md, D-05). If Apple fixes
     * them this fails on purpose: replace the zero check with a type check in TC-M01.
     */
    @ParameterizedTest(name = "TC-M03 media={0} is accepted but returns nothing")
    @ValueSource(strings = {"movie", "shortFilm"})
    @DisplayName("TC-M03 movie and shortFilm are accepted but currently return no results")
    void emptyMediaTypesAreAccepted(String media) {
        assertEquals(0, searchOk(Map.of("term", "love", "media", media, "limit", 5))
                .jsonPath().getInt("resultCount"));
    }

    /** Not documented in API.md (see TEST_PLAN.md, D-06). limit=200 so ranking changes don't matter. */
    @Test
    @DisplayName("TC-M05 media=music includes music videos as well as songs")
    void musicIncludesMusicVideos() {
        List<String> types = results(searchOk(Map.of("term", "love", "media", "music", "limit", 200)))
                .stream().map(ITunesApi::typeOf).toList();

        assertTrue(types.contains("song"), "expected songs, got " + types);
        assertTrue(types.contains("music-video"), "expected a music video, got " + types);
    }

    @Test
    @DisplayName("TC-M06 leaving media out searches all media (the documented default)")
    void defaultMediaIsAll() {
        // Audiobooks have no trackId, so they're identified by collectionId.
        String ids = "results.collect { it.trackId ?: it.collectionId }";
        List<Long> withoutMedia = searchOk(Map.of("term", "love", "limit", 10)).jsonPath().getList(ids, Long.class);
        List<Long> mediaAll = searchOk(Map.of("term", "love", "media", "all", "limit", 10))
                .jsonPath().getList(ids, Long.class);

        assertFalse(withoutMedia.isEmpty(), "expected results");
        assertEquals(mediaAll, withoutMedia);
    }

    @ParameterizedTest(name = "TC-M04 media={0} is rejected")
    @ValueSource(strings = {"bogus", "MUSIC"})
    @DisplayName("TC-M04 unknown or wrongly-cased media values are rejected with HTTP 400")
    void invalidMediaIsRejected(String media) {
        searchRejected(Map.of("term", "jack johnson", "media", media), "mediaType");
    }
}
