package itunes.support;

import io.restassured.filter.Filter;
import io.restassured.filter.FilterContext;
import io.restassured.response.Response;
import io.restassured.specification.FilterableRequestSpecification;
import io.restassured.specification.FilterableResponseSpecification;

/**
 * Keeps at least {@code minGapMs} between requests so the suite stays well under Apple's rate limit.
 * The gap is shared by all tests in the JVM.
 */
final class ThrottleFilter implements Filter {

    private static final Object LOCK = new Object();
    private static long lastRequestAt;

    private final long minGapMs;

    ThrottleFilter(long minGapMs) {
        this.minGapMs = minGapMs;
    }

    @Override
    public Response filter(FilterableRequestSpecification request,
                           FilterableResponseSpecification response,
                           FilterContext ctx) {
        synchronized (LOCK) {
            long wait = lastRequestAt + minGapMs - System.currentTimeMillis();
            if (wait > 0) {
                try {
                    Thread.sleep(wait);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while waiting between requests", e);
                }
            }
            lastRequestAt = System.currentTimeMillis();
        }
        return ctx.next(request, response);
    }
}
