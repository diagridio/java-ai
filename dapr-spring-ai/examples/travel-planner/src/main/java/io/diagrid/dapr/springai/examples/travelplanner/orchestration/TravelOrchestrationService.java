package io.diagrid.dapr.springai.examples.travelplanner.orchestration;

import io.diagrid.dapr.springai.examples.travelplanner.agents.ActivityPlanner;
import io.diagrid.dapr.springai.examples.travelplanner.agents.CityGuide;
import io.diagrid.dapr.springai.examples.travelplanner.agents.FlightFinder;
import io.diagrid.dapr.springai.examples.travelplanner.agents.HotelFinder;
import io.diagrid.dapr.springai.examples.travelplanner.agents.ItineraryFormatter;
import io.diagrid.dapr.springai.examples.travelplanner.agents.WeatherAssistant;
import io.micrometer.context.ContextExecutorService;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * Multi-agent orchestration composed at the application layer, mirroring Spring AI's documented
 * agentic patterns: Chain (sequential), Parallelization (parallel), Routing (conditional), and a
 * loop. Each step calls an agent's {@code ChatClient}, which runs its own tool loop.
 *
 * <p>Each entry point opens a named root {@link Observation} (e.g. {@code travel.plan}) so a whole
 * multi-agent request is <b>one</b> trace with every agent call nested under it — without relying on
 * Boot's servlet HTTP-server span. Sequential calls nest automatically; the parallel branches nest
 * because the pool propagates the trace context (see {@link #pool}). No tracing backend ⇒ the
 * observation is a NOOP.
 */
@Service
public class TravelOrchestrationService {

    private final WeatherAssistant weather;
    private final CityGuide cityGuide;
    private final FlightFinder flightFinder;
    private final HotelFinder hotelFinder;
    private final ActivityPlanner activityPlanner;
    private final ItineraryFormatter formatter;
    private final ObservationRegistry observations;

    // Wrapped for context propagation: the parallel branches run on pool threads, and without this
    // the caller's trace context wouldn't cross the thread boundary — each agent call would start its
    // own root trace instead of nesting under the request's root span. With no tracing backend this
    // is a harmless no-op (nothing to propagate). See the README's Observability section.
    private final ExecutorService pool =
            ContextExecutorService.wrap(Executors.newFixedThreadPool(3));

    public TravelOrchestrationService(WeatherAssistant weather, CityGuide cityGuide,
                                      FlightFinder flightFinder, HotelFinder hotelFinder,
                                      ActivityPlanner activityPlanner, ItineraryFormatter formatter,
                                      ObjectProvider<ObservationRegistry> observationRegistry) {
        this.weather = weather;
        this.cityGuide = cityGuide;
        this.flightFinder = flightFinder;
        this.hotelFinder = hotelFinder;
        this.activityPlanner = activityPlanner;
        this.formatter = formatter;
        // No ObservationRegistry bean (base build, no actuator) ⇒ NOOP: the observations below cost
        // nothing until a tracing backend is present.
        this.observations = observationRegistry.getIfAvailable(() -> ObservationRegistry.NOOP);
    }

    @PreDestroy
    void shutdown() {
        pool.shutdownNow();
    }

    // Run a multi-agent flow inside a named root span so its agent calls nest under one trace.
    private String observe(String name, Supplier<String> flow) {
        return Observation.createNotStarted(name, observations).observe(flow);
    }

    // ── Chain / sequential (TripPrep): weather → city guide ──────────────────────
    public String tripPrep(String city, String cuisine, int days) {
        return observe("travel.trip-prep", () -> {
            weather.checkWeather(city);
            return cityGuide.createGuide(city, cuisine, days);
        });
    }

    // ── Parallelization (QuickResearch): weather + city guide concurrently ───────
    public String quickResearch(String city, String cuisine, int days) {
        return observe("travel.research", () -> {
            CompletableFuture<String> w =
                    CompletableFuture.supplyAsync(() -> weather.checkWeather(city), pool);
            CompletableFuture<String> g =
                    CompletableFuture.supplyAsync(() -> cityGuide.createGuide(city, cuisine, days), pool);
            CompletableFuture.allOf(w, g).join();
            return "=== WEATHER ===\n" + w.join() + "\n\n=== CITY GUIDE ===\n" + g.join();
        });
    }

    // ── Loop (ItineraryRefiner): iterative refinement — draft, then improve each pass ────
    public String itineraryRefiner(String city, String cuisine, int days) {
        return observe("travel.refine", () -> {
            int maxIterations = 2;
            String weatherSummary = weather.checkWeather(city);
            String guide = cityGuide.createGuide(city, cuisine, days);     // pass 1: draft
            for (int i = 1; i < maxIterations; i++) {                      // pass 2+: feed the prior draft back
                guide = cityGuide.refine(guide, weatherSummary, city, cuisine, days);
            }
            return guide;
        });
    }

    // ── Routing / conditional (TravelRouter): route by trip duration ─────────────
    public String travelRouter(String city, String cuisine, int days) {
        return observe("travel.route", () -> {
            String w = weather.checkWeather(city);
            if (days > 1) {
                String g = cityGuide.createGuide(city, cuisine, days);
                return "=== WEATHER ===\n" + w + "\n\n=== CITY GUIDE ===\n" + g;
            }
            return w;
        });
    }

    // ── Nested (TravelPlanner): parallel research → itinerary formatter ──────────
    public String travelPlanner(String origin, String destination, String date,
                                int nights, String interests) {
        return observe("travel.plan", () -> {
            CompletableFuture<String> flights = CompletableFuture.supplyAsync(
                    () -> flightFinder.findFlights(origin, destination, date), pool);
            CompletableFuture<String> hotels = CompletableFuture.supplyAsync(
                    () -> hotelFinder.findHotels(destination, date, nights), pool);
            CompletableFuture<String> activities = CompletableFuture.supplyAsync(
                    () -> activityPlanner.planActivities(destination, interests), pool);
            CompletableFuture.allOf(flights, hotels, activities).join();
            return formatter.format(flights.join(), hotels.join(), activities.join());
        });
    }
}
