package io.diagrid.springai.examples.durablechat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * A plain Spring AI {@code @Tool} bean — nothing durable-specific here. The dapr-spring-ai starter
 * discovers it and runs it as a Dapr Workflow activity.
 *
 * <p>{@code bookFlight} appends to {@code bookings.log} in the working directory, an observable side
 * effect: tail that file to confirm the booking runs exactly once, even if you crash the app
 * mid-call and reissue the same request.
 */
@Component
public class BookingTools {

  private static final Logger LOG = LoggerFactory.getLogger(BookingTools.class);
  private static final Path LOG_FILE = Path.of("bookings.log");

  @Tool(description = "Book a flight to a destination city. Returns a confirmation code.")
  public String bookFlight(@ToolParam(description = "destination city") String city) {
    String confirmation = "BK-" + Math.floorMod(city.toLowerCase().hashCode(), 10000);
    try {
      Files.writeString(
          LOG_FILE,
          "booked flight to " + city + " -> " + confirmation + System.lineSeparator(),
          Files.exists(LOG_FILE) ? StandardOpenOption.APPEND : StandardOpenOption.CREATE);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    LOG.info("SIDE EFFECT: booked flight to {} ({})", city, confirmation);
    return "Flight to " + city + " is booked. Confirmation code " + confirmation + ".";
  }
}
