# dapr-spring-ai-conversation

A Spring AI `ChatModel` whose LLM calls go through the Dapr sidecar's
[Conversation API](https://docs.dapr.io/developing-applications/building-blocks/conversation/)
instead of a provider SDK. LLM traffic leaves the app as a Dapr building-block
call: swap providers by component configuration, keep provider SDKs and API keys
out of the application, and get sidecar features like PII scrubbing for free.

## Capabilities (and honest limits)

Verified against the pinned dapr-sdk 1.18.0, which speaks the **alpha2**
conversation API (`DaprPreviewClient.converseAlpha2`) — note the API itself is
in preview on the Dapr side:

- **Tool calling: supported.** Tool definitions from Spring AI's
  `ToolCallingChatOptions` are advertised to the model, and returned tool calls
  are mapped onto `AssistantMessage.toolCalls`. The model **never executes** a
  tool callback itself (see the durability note below). Multi-turn tool
  conversations round-trip: assistant tool calls and tool results map onto the
  alpha2 assistant/tool message types.
- **Text only.** The Conversation API carries no media; a message with
  multimodal content fails fast instead of silently dropping it.
- **No streaming.** The alpha2 API is single-response; `stream()` throws
  `UnsupportedOperationException` (Spring AI's default) rather than faking a
  stream.
- **Options:** `temperature` is the only portable `ChatOptions` field the
  request carries. Other sampling options (`model`, `maxTokens`, `topP`, …) are
  ignored **with a warning** — the model and its parameters are chosen by the
  Dapr component configuration. Caveat: the pinned SDK transmits temperature
  unconditionally, so an unset temperature reaches the provider as an explicit
  `0.0`; set it if your provider's default matters to you.
- **Response metadata:** per-choice finish reason, plus model name and token
  usage when the component reports them. The response's Dapr context id is
  exposed under the `"dapr-context-id"` metadata key. Nothing is fabricated —
  absent fields stay absent.

## Quick start

```xml
<dependency>
  <groupId>io.diagrid.dapr</groupId>
  <artifactId>dapr-spring-ai-conversation</artifactId>
</dependency>
```

```properties
dapr.spring-ai.conversation.component=my-llm
```

For local development without a provider key, use Dapr's `echo` component:

```yaml
apiVersion: dapr.io/v1alpha1
kind: Component
metadata:
  name: my-llm
spec:
  type: conversation.echo
  version: v1
```

For real use, point the same component name at a provider:

```yaml
apiVersion: dapr.io/v1alpha1
kind: Component
metadata:
  name: my-llm
spec:
  type: conversation.openai
  version: v1
  metadata:
    - name: key
      value: "<OPENAI_API_KEY>"
    - name: model
      value: gpt-4o-mini
```

The app code is plain Spring AI — inject the auto-configured `ChatModel` (or
build a `ChatClient` on it) and call it.

## Properties

| Property | Default | Meaning |
|---|---|---|
| `dapr.spring-ai.conversation.component` | — (**required**) | Dapr conversation component routing LLM traffic |
| `dapr.spring-ai.conversation.context-id` | — | conversation session id handed to the sidecar |
| `dapr.spring-ai.conversation.scrub-pii` | `false` | sidecar obfuscates PII in inputs and outputs |
| `dapr.spring-ai.conversation.temperature` | — | default sampling temperature (see caveat above) |

## Selecting the model

This module follows the same convention as Spring AI's provider starters
(OpenAI, Ollama, …): registration is controlled by `spring.ai.model.chat`.

- Property unset (default): the model registers — like every other chat starter
  on the classpath. With several starters present, Spring reports the ambiguity
  at the first single-`ChatModel` injection point, exactly as it would for
  OpenAI + Ollama together.
- `spring.ai.model.chat=dapr` — pick this model explicitly (other provider
  starters switch off).
- `spring.ai.model.chat=openai` (or any other provider) — this module switches
  off.
- `spring.ai.model.chat=none` — no chat model auto-configures.

User-defined beans win: an app-supplied `DaprConversationChatModel` bean
replaces the auto-configured one, and an app-supplied `DaprPreviewClient` bean
takes precedence over the default client.

## Composing with the durability layer

This model composes with `dapr-spring-ai-starter`'s durable path: the durable
advisor runs the model call inside a workflow activity and advertises tools as
definition-only callbacks that **throw if executed**. This model only ever
*returns* tool calls — the workflow's `ToolInvokeActivity` executes them — so
durable + conversation-API works out of the box: a durable agent whose LLM
traffic flows through the sidecar.
