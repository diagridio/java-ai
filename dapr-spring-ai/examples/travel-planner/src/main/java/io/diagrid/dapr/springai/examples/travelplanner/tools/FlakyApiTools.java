package io.diagrid.dapr.springai.examples.travelplanner.tools;

import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * A deliberately unreliable tool that fails on every odd invocation, to demonstrate the
 * dapr-spring-ai retry policy.
 *
 * <p>Each tool call runs as a durable {@code dsa.tool.invoke} activity. When this tool throws, the
 * activity fails and the Dapr runtime retries it (default policy: up to 3 attempts with exponential
 * backoff) instead of failing the whole workflow. Because a retry re-invokes the tool, the counter
 * advances from the odd (failing) attempt to the next even (succeeding) attempt, so the workflow
 * recovers transparently.
 *
 * <p>The counter is process-global and counts <em>physical</em> invocations (the tool only sees the
 * JSON arguments, not the workflow context, so it cannot tell a fresh call from a retry). Sequence:
 * attempt&nbsp;1 (odd) throws → retried → attempt&nbsp;2 (even) succeeds; attempt&nbsp;3 (odd)
 * throws → retried → attempt&nbsp;4 (even) succeeds; and so on. Every call starts on an odd attempt,
 * so every call demonstrates a crash-and-recover.
 */
public class FlakyApiTools {

    private static final Logger LOG = LoggerFactory.getLogger(FlakyApiTools.class);
    private static final AtomicInteger CALLS = new AtomicInteger();

    @Tool(description = "Confirm a travel booking with the reservation provider and return a confirmation code")
    public String confirmBooking(@ToolParam(description = "the booking reference") String reference) {
        int attempt = CALLS.incrementAndGet();
        if (attempt % 2 != 0) {
            LOG.warn(">>> confirmBooking({}) — attempt #{} (odd): simulating a transient provider failure",
                    reference, attempt);
            throw new RuntimeException(
                    "Booking provider temporarily unavailable (simulated failure on odd run #" + attempt + ")");
        }
        LOG.info(">>> confirmBooking({}) — attempt #{} (even): success", reference, attempt);
        return "Booking " + reference + " confirmed. Confirmation code: BK-" + (1000 + attempt);
    }
}
