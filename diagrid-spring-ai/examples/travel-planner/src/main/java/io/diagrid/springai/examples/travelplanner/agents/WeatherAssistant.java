package io.diagrid.springai.examples.travelplanner.agents;

import io.diagrid.springai.examples.travelplanner.tools.WeatherTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

/** Checks the current weather for a city. Single tool, single step. */
@Component
public class WeatherAssistant {

    private static final String SYSTEM = """
            You are a weather assistant. Check the current weather for the requested city
            using the getWeather tool and provide a brief summary including temperature,
            conditions, and what to wear.""";

    private final ChatClient chat;

    public WeatherAssistant(ChatClient.Builder builder) {
        this.chat = builder
                .defaultSystem(SYSTEM)
                .defaultTools(new WeatherTools())
                .build();
    }

    public String checkWeather(String city) {
        return chat.prompt()
                .user("Check the current weather in " + city + ".")
                .call()
                .content();
    }
}
