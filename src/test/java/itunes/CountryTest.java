package itunes;

import static itunes.support.ITunesApi.results;
import static itunes.support.ITunesApi.searchOk;
import static itunes.support.ITunesApi.searchRejected;
import static itunes.support.ITunesApi.storeUrlOf;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("country parameter")
class CountryTest {

    private static List<Map<String, Object>> songs(String country) {
        Map<String, Object> params = new HashMap<>(Map.of("term", "jack johnson", "media", "music", "limit", 5));
        if (country != null) {
            params.put("country", country);
        }
        return results(searchOk(params));
    }

    /** Every result links to the given store (e.g. {@code /gb/}) and is priced in the given currency. */
    private static void assertStore(List<Map<String, Object>> results, String store, String currency) {
        assertFalse(results.isEmpty(), "expected results");
        assertAll(results.stream().map(r -> () -> {
            assertTrue(storeUrlOf(r).contains("/" + store + "/"), "link not in the " + store + " store: " + storeUrlOf(r));
            assertEquals(currency, r.get("currency"));
        }));
    }

    @Test
    @DisplayName("TC-C01 without a country, the US store is searched")
    void defaultCountryIsUs() {
        assertStore(songs(null), "us", "USD");
    }

    @ParameterizedTest(name = "TC-C02 country={0}")
    @ValueSource(strings = {"GB", "gb"})
    @DisplayName("TC-C02 a country code searches that store, in either case")
    void countrySelectsStore(String country) {
        assertStore(songs(country), country.toLowerCase(Locale.ROOT), "GBP");
    }

    @ParameterizedTest(name = "TC-C03 country={0} is rejected")
    @ValueSource(strings = {"ZZ", "USA"})
    @DisplayName("TC-C03 unknown or non-alpha-2 country codes are rejected with HTTP 400")
    void invalidCountryIsRejected(String country) {
        searchRejected(Map.of("term", "jack johnson", "country", country), "country");
    }
}
