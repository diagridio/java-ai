package io.diagrid.springai.examples.travelplanner.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/** Mock weather data. In production this would call a real weather API. */
public class WeatherTools {

    @Tool(description = "Get the current weather for a city")
    public String getWeather(@ToolParam(description = "the city name") String city) {
        if (city == null || city.isBlank()) {
            return "Please specify a city name.";
        }
        return switch (city.toLowerCase()) {
            case "paris" -> "Paris: 22C, partly cloudy, light breeze. Pleasant for walking.";
            case "tokyo" -> "Tokyo: 28C, humid, chance of afternoon rain. Carry an umbrella.";
            case "rome" -> "Rome: 30C, sunny and hot. Stay hydrated, wear sunscreen.";
            case "new york", "nyc" -> "New York: 18C, clear skies, cool evening expected.";
            case "london" -> "London: 15C, overcast with light drizzle. Bring a jacket.";
            default -> city + ": 20C, clear skies, comfortable conditions.";
        };
    }
}
