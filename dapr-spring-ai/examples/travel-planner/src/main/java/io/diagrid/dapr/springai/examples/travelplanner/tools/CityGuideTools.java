package io.diagrid.dapr.springai.examples.travelplanner.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * Mock city guide data. In production these would call real APIs
 * (Google Places, TripAdvisor, transit agencies, etc.).
 */
public class CityGuideTools {

    @Tool(description = "Find popular tourist attractions and things to do in a city")
    public String findAttractions(@ToolParam(description = "the city name") String city) {
        if (city == null || city.isBlank()) {
            return "Please specify a city name.";
        }
        return switch (city.toLowerCase()) {
            case "paris" -> "1. Eiffel Tower — iconic landmark, open 9:30-23:00, €26 adult\n"
                    + "2. Louvre Museum — world's largest art museum, closed Tuesdays, €17\n"
                    + "3. Sacré-Cœur — hilltop basilica in Montmartre, free entry\n"
                    + "4. Seine River Cruise — 1hr scenic boat tour, €15\n"
                    + "5. Musée d'Orsay — impressionist art, closed Mondays, €16";
            case "tokyo" -> "1. Senso-ji Temple — oldest temple in Tokyo, free entry\n"
                    + "2. Shibuya Crossing — world's busiest intersection, free\n"
                    + "3. Meiji Shrine — serene forest shrine, free entry\n"
                    + "4. TeamLab Borderless — digital art museum, ¥3,800\n"
                    + "5. Tsukiji Outer Market — street food & fresh seafood, free to browse";
            case "rome" -> "1. Colosseum — ancient amphitheatre, €16, book ahead\n"
                    + "2. Vatican Museums & Sistine Chapel — €17, closed Sundays\n"
                    + "3. Trevi Fountain — baroque masterpiece, free, visit early morning\n"
                    + "4. Pantheon — best-preserved Roman building, free entry\n"
                    + "5. Roman Forum — ancient ruins, €16 (combo with Colosseum)";
            default -> "1. City Center Walking Tour — free/tips-based\n"
                    + "2. Main Museum — varies\n"
                    + "3. Local Market — free to browse";
        };
    }

    @Tool(description = "Search for top-rated restaurants in a city, optionally filtered by cuisine type")
    public String searchRestaurants(@ToolParam(description = "the city name") String city,
                                    @ToolParam(description = "cuisine type like italian, japanese, french, or any") String cuisine) {
        if (city == null || city.isBlank()) {
            return "Please specify a city name.";
        }
        String c = cuisine != null ? cuisine.toLowerCase() : "any";
        if (city.equalsIgnoreCase("paris")) {
            return switch (c) {
                case "french" -> "1. Le Comptoir du Panthéon — classic bistro, €€, 4.6★\n"
                        + "2. Chez Janou — provençal cuisine, €€, 4.5★\n"
                        + "3. Le Bouillon Chartier — historic brasserie, €, 4.3★";
                case "japanese" -> "1. Kodawari Ramen — authentic ramen, €€, 4.7★\n"
                        + "2. Zen — omakase sushi, €€€, 4.5★";
                default -> "1. Le Comptoir du Panthéon — french bistro, €€, 4.6★\n"
                        + "2. Pink Mamma — italian, €€, 4.4★\n"
                        + "3. Kodawari Ramen — japanese, €€, 4.7★";
            };
        }
        if (city.equalsIgnoreCase("tokyo")) {
            return c.equals("japanese")
                    ? "1. Sukiyabashi Jiro — sushi, €€€€, 4.9★\n"
                            + "2. Ichiran Shibuya — ramen, €, 4.6★\n"
                            + "3. Gonpachi Nishi-Azabu — izakaya, €€, 4.5★"
                    : "1. Sukiyabashi Jiro — sushi, €€€€, 4.9★\n"
                            + "2. Savoy — neapolitan pizza, €€, 4.4★\n"
                            + "3. Ichiran Shibuya — ramen, €, 4.6★";
        }
        if (city.equalsIgnoreCase("rome")) {
            return "1. Da Enzo al 29 — roman trattoria, €€, 4.7★\n"
                    + "2. Roscioli — deli & restaurant, €€€, 4.6★\n"
                    + "3. Pizzarium Bonci — pizza al taglio, €, 4.8★";
        }
        return "1. Local Bistro — mixed cuisine, €€, 4.3★\n"
                + "2. Street Food Market — various, €, 4.5★";
    }

    @Tool(description = "Get public transport information for getting around a city")
    public String getTransportInfo(@ToolParam(description = "the city name") String city) {
        if (city == null || city.isBlank()) {
            return "Please specify a city name.";
        }
        return switch (city.toLowerCase()) {
            case "paris" -> "Metro: 16 lines, runs 5:30-01:00 (02:00 Fri/Sat). Single ticket €2.15, "
                    + "day pass (Navigo Jour) €8.45. Covers zones 1-5.\n"
                    + "Bus: extensive network, same tickets as metro.\n"
                    + "Vélib: bike-share, €5/day for 30-min rides.\n"
                    + "Tip: Buy a carnet of 10 tickets for €17.35 (save 20%).";
            case "tokyo" -> "Metro/Subway: 13 lines + JR Yamanote loop, runs 05:00-00:30.\n"
                    + "Suica/Pasmo IC card: tap-and-go, rechargeable. ~¥200-400 per trip.\n"
                    + "Day pass: Tokyo Subway 24h ticket ¥600.\n"
                    + "Tip: Get a Suica card at any station — works on all trains, buses, and convenience stores.";
            case "rome" -> "Metro: 3 lines (A, B, C), runs 05:30-23:30 (01:30 Fri/Sat). Single ticket €1.50.\n"
                    + "Bus/Tram: extensive network, same tickets. Validate on board!\n"
                    + "Roma Pass: €32 for 48h — includes transport + 1 museum entry.\n"
                    + "Tip: Rome is very walkable — most historic center attractions are within 30 min walk.";
            default -> "Public transport available. Check local transit authority for routes and fares.\n"
                    + "Tip: Walking is often the best way to explore city centers.";
        };
    }
}
