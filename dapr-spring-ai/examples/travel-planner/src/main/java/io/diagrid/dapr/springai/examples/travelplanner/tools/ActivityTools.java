package io.diagrid.dapr.springai.examples.travelplanner.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/** Mock activity search data. In production this would call a real activities/tours API. */
public class ActivityTools {

    @Tool(description = "Search for activities and attractions in a city matching the given interests")
    public String searchActivities(@ToolParam(description = "the city name") String city,
                                   @ToolParam(description = "comma-separated interests") String interests) {
        return switch (city.toLowerCase()) {
            case "paris" -> String.format(
                    "Top activities in Paris matching '%s': "
                            + "(1) Louvre Museum guided tour - 3 hours, $35, "
                            + "(2) Seine River dinner cruise - 2 hours, $85, "
                            + "(3) Montmartre walking food tour - 3 hours, $60, "
                            + "(4) Eiffel Tower skip-the-line tickets - $30, "
                            + "(5) Day trip to Versailles - 6 hours, $75",
                    interests);
            case "tokyo" -> String.format(
                    "Top activities in Tokyo matching '%s': "
                            + "(1) Tsukiji Outer Market food tour - 3 hours, $45, "
                            + "(2) Traditional tea ceremony in Asakusa - 1 hour, $25, "
                            + "(3) Akihabara tech and anime walking tour - 2 hours, $30, "
                            + "(4) Mount Fuji day trip - 10 hours, $120, "
                            + "(5) Sumo wrestling morning practice viewing - 2 hours, $50",
                    interests);
            case "rome" -> String.format(
                    "Top activities in Rome matching '%s': "
                            + "(1) Colosseum and Roman Forum guided tour - 3 hours, $45, "
                            + "(2) Vatican Museums and Sistine Chapel - 4 hours, $55, "
                            + "(3) Trastevere street food tour - 3 hours, $50, "
                            + "(4) Cooking class: pasta and tiramisu - 3 hours, $70, "
                            + "(5) Appian Way e-bike tour - 3 hours, $40",
                    interests);
            default -> String.format(
                    "Top activities in %s matching '%s': "
                            + "(1) City walking tour - 2 hours, $25, "
                            + "(2) Local food tasting - 3 hours, $45, "
                            + "(3) Museum pass - full day, $30",
                    city, interests);
        };
    }
}
