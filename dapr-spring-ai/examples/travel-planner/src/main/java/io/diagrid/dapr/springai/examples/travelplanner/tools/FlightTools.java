package io.diagrid.dapr.springai.examples.travelplanner.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/** Mock flight search data. In production this would call a real flights API. */
public class FlightTools {

    @Tool(description = "Search for available flights between two cities on a given date")
    public String searchFlights(@ToolParam(description = "departure city") String origin,
                                @ToolParam(description = "destination city") String destination,
                                @ToolParam(description = "travel date") String date) {
        return switch (destination.toLowerCase()) {
            case "paris" -> String.format(
                    "Found 3 flights from %s to Paris on %s: "
                            + "(1) Air France AF101 departing 08:00, arriving 11:30 - $450, "
                            + "(2) Delta DL200 departing 12:15, arriving 15:45 - $380, "
                            + "(3) United UA303 departing 18:00, arriving 21:30 - $520",
                    origin, date);
            case "tokyo" -> String.format(
                    "Found 2 flights from %s to Tokyo on %s: "
                            + "(1) JAL JL001 departing 10:00, arriving 14:00+1 - $890, "
                            + "(2) ANA NH100 departing 23:00, arriving 04:00+2 - $750",
                    origin, date);
            case "rome" -> String.format(
                    "Found 2 flights from %s to Rome on %s: "
                            + "(1) Alitalia AZ610 departing 09:30, arriving 12:00 - $410, "
                            + "(2) Lufthansa LH340 departing 14:00, arriving 17:30 - $470",
                    origin, date);
            default -> String.format(
                    "Found 1 flight from %s to %s on %s: "
                            + "Generic Air GA999 departing 10:00, arriving 16:00 - $500",
                    origin, destination, date);
        };
    }
}
