package io.diagrid.springai.examples.travelplanner.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/** Mock hotel search data. In production this would call a real hotel booking API. */
public class HotelTools {

    @Tool(description = "Search for available hotels in a city for a given number of nights")
    public String searchHotels(@ToolParam(description = "the city name") String city,
                               @ToolParam(description = "check-in date") String checkIn,
                               @ToolParam(description = "number of nights") int nights) {
        return switch (city.toLowerCase()) {
            case "paris" -> String.format(
                    "Found 3 hotels in Paris (check-in %s, %d nights): "
                            + "(1) Hotel Le Marais - 4 star, $180/night, near Notre-Dame, "
                            + "(2) Montmartre Boutique - 3 star, $120/night, near Sacre-Coeur, "
                            + "(3) Champs-Elysees Grand - 5 star, $350/night, near Arc de Triomphe",
                    checkIn, nights);
            case "tokyo" -> String.format(
                    "Found 3 hotels in Tokyo (check-in %s, %d nights): "
                            + "(1) Shinjuku Inn - 3 star, $95/night, near Shinjuku Station, "
                            + "(2) Shibuya Crossing Hotel - 4 star, $160/night, near Shibuya, "
                            + "(3) Imperial Tokyo - 5 star, $400/night, near Imperial Palace",
                    checkIn, nights);
            case "rome" -> String.format(
                    "Found 3 hotels in Rome (check-in %s, %d nights): "
                            + "(1) Trastevere B&B - 3 star, $110/night, charming neighborhood, "
                            + "(2) Hotel Colosseum - 4 star, $200/night, near Colosseum, "
                            + "(3) Vatican Suites - 4 star, $170/night, near Vatican City",
                    checkIn, nights);
            default -> String.format(
                    "Found 1 hotel in %s (check-in %s, %d nights): "
                            + "City Center Hotel - 3 star, $130/night, central location",
                    city, checkIn, nights);
        };
    }
}
