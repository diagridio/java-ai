package io.diagrid.dapr.springai.examples.travelplanner.tools;

import java.util.Locale;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

/**
 * A <b>global</b> tool. Unlike the other travel-planner tools — plain classes attached per-agent via
 * {@code .defaultTools(new XxxTools())} — this is a Spring {@code @Component}. The dapr-spring-ai
 * durability layer discovers every {@code @Tool} bean and offers it to <b>every</b> durable agent, so
 * currency conversion is available to all of them without any agent wiring it, and it appears on
 * every agent's record in the agent registry (including tool-less agents like {@code budgetAdvisor}).
 */
@Component
public class CurrencyTools {

  @Tool(description = "Convert an amount of money between currencies (ISO codes, e.g. USD, EUR, JPY)")
  public String convertCurrency(double amount, String fromCurrency, String toCurrency) {
    double converted = amount * perUsd(toCurrency) / perUsd(fromCurrency);
    return String.format(
        Locale.ROOT,
        "%.2f %s = %.2f %s",
        amount,
        fromCurrency.toUpperCase(Locale.ROOT),
        converted,
        toCurrency.toUpperCase(Locale.ROOT));
  }

  // Mock rates (units of the currency per 1 USD) — demo data, no live FX.
  private static double perUsd(String currency) {
    return switch (currency.toUpperCase(Locale.ROOT)) {
      case "EUR" -> 0.92;
      case "GBP" -> 0.79;
      case "JPY" -> 157.0;
      default -> 1.0; // USD and unknown
    };
  }
}
