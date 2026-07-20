package io.diagrid.dapr.springai.examples.travelplanner.web;

import io.diagrid.dapr.springai.examples.travelplanner.agents.CityGuide;
import io.diagrid.dapr.springai.examples.travelplanner.agents.WeatherAssistant;
import io.diagrid.dapr.springai.examples.travelplanner.orchestration.TravelOrchestrationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST endpoints mirroring the other travel-planner modules (same paths, params, and defaults).
 * Single-agent endpoints call an agent directly; the rest delegate to the orchestration service.
 */
@RestController
public class TravelControllers {

    private final WeatherAssistant weather;
    private final CityGuide cityGuide;
    private final TravelOrchestrationService travel;

    public TravelControllers(WeatherAssistant weather, CityGuide cityGuide,
                             TravelOrchestrationService travel) {
        this.weather = weather;
        this.cityGuide = cityGuide;
        this.travel = travel;
    }

    /** Single agent, one tool. */
    @GetMapping("/weather")
    public String weather(@RequestParam(defaultValue = "Paris") String city) {
        return weather.checkWeather(city);
    }

    /** Single agent, three tools, multi-step reasoning. */
    @GetMapping("/guide")
    public String guide(@RequestParam(defaultValue = "Paris") String city,
                        @RequestParam(defaultValue = "any") String cuisine,
                        @RequestParam(defaultValue = "3") int days) {
        return cityGuide.createGuide(city, cuisine, days);
    }

    /** Sequential: weather → city guide. */
    @GetMapping("/trip")
    public String trip(@RequestParam(defaultValue = "Paris") String city,
                       @RequestParam(defaultValue = "any") String cuisine,
                       @RequestParam(defaultValue = "3") int days) {
        return travel.tripPrep(city, cuisine, days);
    }

    /** Parallel: weather + city guide concurrently. */
    @GetMapping("/research")
    public String research(@RequestParam(defaultValue = "Paris") String city,
                           @RequestParam(defaultValue = "any") String cuisine,
                           @RequestParam(defaultValue = "3") int days) {
        return travel.quickResearch(city, cuisine, days);
    }

    /** Loop: weather → guide, repeated twice. */
    @GetMapping("/refine")
    public String refine(@RequestParam(defaultValue = "Paris") String city,
                         @RequestParam(defaultValue = "any") String cuisine,
                         @RequestParam(defaultValue = "3") int days) {
        return travel.itineraryRefiner(city, cuisine, days);
    }

    /** Conditional: days&lt;=1 → weather only; days&gt;1 → weather + guide. */
    @GetMapping("/route")
    public String route(@RequestParam(defaultValue = "Paris") String city,
                        @RequestParam(defaultValue = "any") String cuisine,
                        @RequestParam(defaultValue = "3") int days) {
        return travel.travelRouter(city, cuisine, days);
    }

    /** Nested: parallel flight/hotel/activity research → itinerary formatter. */
    @GetMapping("/travel/plan")
    public String plan(@RequestParam(defaultValue = "New York") String origin,
                       @RequestParam(defaultValue = "Paris") String destination,
                       @RequestParam(defaultValue = "2025-07-01") String date,
                       @RequestParam(defaultValue = "5") int nights,
                       @RequestParam(defaultValue = "history, food, culture") String interests) {
        return travel.travelPlanner(origin, destination, date, nights, interests);
    }
}
