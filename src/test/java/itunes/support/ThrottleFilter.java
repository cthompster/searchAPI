package itunes.support;

import io.restassured.filter.Filter;
import io.restassured.filter.FilterContext;
import io.restassured.response.Response;
import io.restassured.specification.FilterableRequestSpecification;
import io.restassured.specification.FilterableResponseSpecification;
import java.util.concurrent.TimeUnit;

/**
 * Keeps at least {@code minGapMs} between requests so the suite stays well under Apple's rate limit.
 * The gap is shared by all tests in the JVM and measured with {@link System#nanoTime()}, so changes
 * to the wall clock don't affect it.
 */
final class ThrottleFilter implements Filter {

    private static final Object LOCK = new Object();
    private static boolean anySent;
    private static long lastRequestAt;

    private final long minGapNanos;

    ThrottleFilter(long minGapMs) {
        this.minGapNanos = TimeUnit.MILLISECONDS.toNanos(minGapMs);
    }

    @Override
    public Response filter(FilterableRequestSpecification request,
                           FilterableResponseSpecification response,
                           FilterContext ctx) {
        synchronized (LOCK) {
            if (anySent) {
                long waitNanos = minGapNanos - (System.nanoTime() - lastRequestAt);
                if (waitNanos > 0) {
                    try {
                        TimeUnit.NANOSECONDS.sleep(waitNanos);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("Interrupted while waiting between requests", e);
                    }
                }
            }
            anySent = true;
            lastRequestAt = System.nanoTime();
        }
        return ctx.next(request, response);
    }
}
