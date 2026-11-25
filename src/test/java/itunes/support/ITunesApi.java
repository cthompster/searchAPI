package itunes.support;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.startsWith;

import io.restassured.RestAssured;
import io.restassured.builder.RequestSpecBuilder;
import io.restassured.config.EncoderConfig;
import io.restassured.config.RestAssuredConfig;
import io.restassured.parsing.Parser;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Shared setup for calls to the iTunes Search API.
 *
 * <p>Settings (override with {@code -Dkey=value}):
 * <ul>
 *   <li>{@code baseUri}: default {@code https://itunes.apple.com}</li>
 *   <li>{@code requestDelayMs}: minimum gap between requests, default 3000. Apple throttles the
 *       API at roughly 20 requests a minute.</li>
 * </ul>
 */
public final class ITunesApi {

    public static final String BASE_URI = System.getProperty("baseUri", "https://itunes.apple.com");
    public static final String SEARCH_PATH = "/search";

    /** The API answers with JSON but labels it {@code text/javascript}. */
    public static final String CONTENT_TYPE = "text/javascript";

    static {
        RestAssured.registerParser(CONTENT_TYPE, Parser.JSON);
        RestAssured.enableLoggingOfRequestAndResponseIfValidationFails();
    }

    private static final RequestSpecification REQUEST = new RequestSpecBuilder()
            .setBaseUri(BASE_URI)
            .setBasePath(SEARCH_PATH)
            .setConfig(RestAssuredConfig.config().encoderConfig(
                    EncoderConfig.encoderConfig().defaultQueryParameterCharset("UTF-8")))
            .addFilter(new ThrottleFilter(Long.getLong("requestDelayMs", 3000L)))
            .build();

    private ITunesApi() {
    }

    /** A request spec for {@code /search}, ready for query parameters. */
    public static RequestSpecification search() {
        return given().spec(REQUEST);
    }

    /**
     * Sends a search and checks the successful-search contract: HTTP 200, the JSON content type,
     * {@code resultCount} and {@code results} present, and {@code resultCount} equal to the number of
     * results.
     */
    public static Response searchOk(Map<String, ?> params) {
        return checkOk(search().queryParams(params).when().get());
    }

    /** Checks the successful-search contract on a response (see {@link #searchOk}). */
    public static Response checkOk(Response response) {
        response.then()
                .statusCode(200)
                .contentType(startsWith(CONTENT_TYPE))
                .body("$", hasKey("resultCount"))
                .body("$", hasKey("results"))
                .body("resultCount", equalTo(response.path("results.size()")));
        return response;
    }

    /**
     * Sends a search that the API should reject and checks the error contract: HTTP 400, the JSON
     * content type, an {@code errorMessage} naming {@code invalidKey}, and the {@code queryParameters}
     * help object.
     */
    public static Response searchRejected(Map<String, ?> params, String invalidKey) {
        Response response = search().queryParams(params).when().get();
        response.then()
                .statusCode(400)
                .contentType(startsWith(CONTENT_TYPE))
                .body("errorMessage", equalTo("Invalid value(s) for key(s): [" + invalidKey + "]"))
                .body("$", hasKey("queryParameters"));
        return response;
    }

    /** The results array as a list of JSON objects. */
    public static List<Map<String, Object>> results(Response response) {
        return response.jsonPath().getList("results");
    }

    /** What a result is: its {@code kind} if it has one (songs, apps...), else its {@code wrapperType}. */
    public static String typeOf(Map<String, Object> result) {
        Object kind = result.get("kind");
        return String.valueOf(kind != null ? kind : result.get("wrapperType"));
    }

    /** The store link of a result: the track's, or the collection's when there is no track. */
    public static String storeUrlOf(Map<String, Object> result) {
        Object url = result.get("trackViewUrl");
        return String.valueOf(url != null ? url : result.get("collectionViewUrl"));
    }

    /** Lower-cases a field for case-insensitive comparisons. */
    public static String lower(Object value) {
        return Objects.toString(value, "").toLowerCase(Locale.ROOT);
    }

    /**
     * How far a result count may drift from {@code limit}. The live API returns a few results more
     * or fewer when results of several kinds are merged (e.g. 207 for limit 200, 23 for 25), so
     * counts are checked within 10% (at least 2) instead of exactly.
     */
    public static int limitTolerance(int limit) {
        return Math.max(2, (int) Math.ceil(limit * 0.10));
    }
}
