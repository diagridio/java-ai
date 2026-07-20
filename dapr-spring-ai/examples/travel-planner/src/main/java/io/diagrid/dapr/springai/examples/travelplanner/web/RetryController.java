package io.diagrid.dapr.springai.examples.travelplanner.web;

import io.diagrid.dapr.springai.examples.travelplanner.agents.BookingAgent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoint that exercises the dapr-spring-ai retry policy. The {@link BookingAgent} uses a tool
 * that throws on every odd invocation; the tool runs as a durable activity, so the Dapr runtime
 * retries it instead of failing the workflow.
 *
 * <pre>
 * # Every call starts on an odd attempt: the tool throws, the runtime retries it, and the even
 * # attempt succeeds, so the workflow still completes. Watch the app log for
 * # ">>> confirmBooking ... (odd): simulating ..." followed by a successful retry, and
 * # `dapr workflow history` for the retried activity.
 * curl "http://localhost:8080/retry-test?reference=ABC123"
 * </pre>
 */
@RestController
public class RetryController {

    private final BookingAgent booking;

    public RetryController(BookingAgent booking) {
        this.booking = booking;
    }

    @GetMapping("/retry-test")
    public String retryTest(@RequestParam(defaultValue = "ABC123") String reference) {
        return booking.confirmBooking(reference);
    }
}
