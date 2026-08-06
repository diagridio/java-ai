package io.diagrid.springai.durable.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The trace-propagation carrier is a new field on {@link AgentRequest}, which durabletask serializes
 * as the workflow input. Guard that the record (with the {@code Map<String,String>} carrier) survives
 * a Jackson round-trip so the carrier reaches the worker intact.
 */
class AgentRequestSerializationTest {

  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void traceContextRoundTrips() throws Exception {
    Map<String, String> carrier =
        Map.of("traceparent", "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01", "tracestate", "x=1");
    AgentRequest request = new AgentRequest(List.of(), List.of(), ChatOptionsSpec.empty(), carrier);

    AgentRequest back = mapper.readValue(mapper.writeValueAsString(request), AgentRequest.class);

    assertEquals(carrier, back.traceContext());
    assertEquals(request, back);
  }

  @Test
  void absentTraceContextRoundTripsAsNull() throws Exception {
    AgentRequest request = new AgentRequest(List.of(), List.of(), ChatOptionsSpec.empty());

    AgentRequest back = mapper.readValue(mapper.writeValueAsString(request), AgentRequest.class);

    assertEquals(null, back.traceContext());
    assertEquals(request, back);
  }
}
