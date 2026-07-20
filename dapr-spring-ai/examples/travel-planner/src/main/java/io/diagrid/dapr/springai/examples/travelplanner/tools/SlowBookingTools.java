package io.diagrid.dapr.springai.examples.travelplanner.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * A deliberately slow booking tool that opens a window for the crash-recovery demo.
 *
 * <p><b>Why a {@code @Tool} Spring bean (not {@code .defaultTools(new SlowBookingTools())}).</b> This
 * is the crux of the demo: the durability layer rediscovers {@code @Tool} <em>beans</em> at startup
 * (see {@code DiscoveredTools}), so after the app is killed mid-tool the tool is re-registered on the
 * fresh worker and the resumed activity can run it. A request-scoped tool attached per-call is
 * registered in-memory at call time and is NOT re-registered after a cold restart — the resumed
 * activity would fail to resolve it. Being a global bean, it is offered to every durable agent (the
 * documented agent-scoped-vs-crash-safe tradeoff); that is acceptable here and mirrors
 * {@code CurrencyTools}.
 *
 * <p>The tool runs as a durable {@code dsa.tool.invoke} activity: it logs a start marker, sleeps for
 * {@code delaySeconds}, then logs a commit marker and returns a confirmation code. The sleep is the
 * window in which you SIGKILL the app (see {@code CrashRecoveryController}). Killing there interrupts
 * this activity mid-flight; on restart the durable runtime re-runs this (incomplete) activity from the
 * start, while NOT re-running any activity that already completed before the crash.
 *
 * <p>Tool name {@code commitReservation} is intentionally distinct from {@code FlakyApiTools}'
 * {@code confirmBooking}: the workflow's tool registry resolves by bare name, process-wide, so two
 * different tools must not share a name. The confirmation code is derived from the reference, so a
 * re-attached call returns the <em>same</em> code — visible proof the booking was not redone.
 */
@Component
public class SlowBookingTools {

    private static final Logger LOG = LoggerFactory.getLogger(SlowBookingTools.class);

    private final int delaySeconds;

    public SlowBookingTools(@Value("${travel-planner.crash-demo.delay-seconds:30}") int delaySeconds) {
        this.delaySeconds = delaySeconds;
    }

    @Tool(name = "commitReservation",
            description = "Commit a travel reservation with the provider and return a confirmation code")
    public String commitReservation(@ToolParam(description = "the booking reference") String reference) {
        LOG.warn(">>> commitReservation({}) — committing over ~{}s. KILL THE APP NOW to test crash"
                + " recovery (POST /crash/kill, or kill -9). It resumes on restart.", reference, delaySeconds);
        try {
            Thread.sleep(delaySeconds * 1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("commitReservation interrupted", e);
        }
        String code = "BK-" + Integer.toHexString(reference.hashCode()).toUpperCase();
        LOG.info(">>> commitReservation({}) — committed. Confirmation code: {}", reference, code);
        return "Booking " + reference + " confirmed. Confirmation code: " + code;
    }
}
