package io.diagrid.springai.examples.travelplanner.orchestration;

import io.diagrid.springai.examples.travelplanner.agents.ActivityPlanner;
import io.diagrid.springai.examples.travelplanner.agents.CityGuide;
import io.diagrid.springai.examples.travelplanner.agents.FlightFinder;
import io.diagrid.springai.examples.travelplanner.agents.HotelFinder;
import io.diagrid.springai.examples.travelplanner.agents.ItineraryFormatter;
import io.diagrid.springai.examples.travelplanner.agents.WeatherAssistant;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.stereotype.Service;

/**
 * Multi-agent orchestration composed at the application layer, mirroring Spring AI's documented
 * agentic patterns: Chain (sequential), Parallelization (parallel), Routing (conditional), and a
 * loop. Each step calls an agent's {@code ChatClient}, which runs its own tool loop.
 */
@Service
public class TravelOrchestrationService {

    private final WeatherAssistant weather;
    private final CityGuide cityGuide;
    private final FlightFinder flightFinder;
    private final HotelFinder hotelFinder;
    private final ActivityPlanner activityPlanner;
    private final ItineraryFormatter formatter;

    private final ExecutorService pool = Executors.newFixedThreadPool(3);

    public TravelOrchestrationService(WeatherAssistant weather, CityGuide cityGuide,
                                      FlightFinder flightFinder, HotelFinder hotelFinder,
                                      ActivityPlanner activityPlanner, ItineraryFormatter formatter) {
        this.weather = weather;
        this.cityGuide = cityGuide;
        this.flightFinder = flightFinder;
        this.hotelFinder = hotelFinder;
        this.activityPlanner = activityPlanner;
        this.formatter = formatter;
    }

    @PreDestroy
    void shutdown() {
        pool.shutdownNow();
    }

    // ── Chain / sequential (TripPrep): weather → city guide ──────────────────────
    public String tripPrep(String city, String cuisine, int days) {
        weather.checkWeather(city);
        return cityGuide.createGuide(city, cuisine, days);
    }

    // ── Parallelization (QuickResearch): weather + city guide concurrently ───────
    public String quickResearch(String city, String cuisine, int days) {
        CompletableFuture<String> w =
                CompletableFuture.supplyAsync(() -> weather.checkWeather(city), pool);
        CompletableFuture<String> g =
                CompletableFuture.supplyAsync(() -> cityGuide.createGuide(city, cuisine, days), pool);
        CompletableFuture.allOf(w, g).join();
        return "=== WEATHER ===\n" + w.join() + "\n\n=== CITY GUIDE ===\n" + g.join();
    }

    // ── Loop (ItineraryRefiner): iterative refinement — draft, then improve each pass ────
    public String itineraryRefiner(String city, String cuisine, int days) {
        int maxIterations = 2;
        String weatherSummary = weather.checkWeather(city);
        String guide = cityGuide.createGuide(city, cuisine, days);     // pass 1: draft
        for (int i = 1; i < maxIterations; i++) {                      // pass 2+: feed the prior draft back
            guide = cityGuide.refine(guide, weatherSummary, city, cuisine, days);
        }
        return guide;
    }

    // ── Routing / conditional (TravelRouter): route by trip duration ─────────────
    public String travelRouter(String city, String cuisine, int days) {
        String w = weather.checkWeather(city);
        if (days > 1) {
            String g = cityGuide.createGuide(city, cuisine, days);
            return "=== WEATHER ===\n" + w + "\n\n=== CITY GUIDE ===\n" + g;
        }
        return w;
    }

    // ── Nested (TravelPlanner): parallel research → itinerary formatter ──────────
    public String travelPlanner(String origin, String destination, String date,
                                int nights, String interests) {
        CompletableFuture<String> flights = CompletableFuture.supplyAsync(
                () -> flightFinder.findFlights(origin, destination, date), pool);
        CompletableFuture<String> hotels = CompletableFuture.supplyAsync(
                () -> hotelFinder.findHotels(destination, date, nights), pool);
        CompletableFuture<String> activities = CompletableFuture.supplyAsync(
                () -> activityPlanner.planActivities(destination, interests), pool);
        CompletableFuture.allOf(flights, hotels, activities).join();
        return formatter.format(flights.join(), hotels.join(), activities.join());
    }
}
