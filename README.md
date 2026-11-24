# jpi-mini

A mini Java port of pi (pi-ai + pi-agent-core): LLM providers, streaming and an agent loop with tool calling.

This is an unofficial, partial Java port of [pi](https://github.com/badlogic/pi-mono), based on commit
[`d5b3de2`](https://github.com/badlogic/pi-mono/commit/d5b3de2). It is not affiliated with pi's authors.
Licensed MIT. [THIRD_PARTY.md](THIRD_PARTY.md) includes the pi and models.dev notices.

The `io.github.izakyl.jpi.ai`, `.agent` and `.auth` packages target Java 17 and use Gson 2.10.1.
Callers provide models, credentials, prompts and tools.

## Build and consume

For the public release, add JitPack to your consuming Gradle project's repositories and choose the
artifact for your environment. These coordinates use [JitPack's multi-module format](https://docs.jitpack.io/building/#multi-module-projects).

```groovy
repositories {
    mavenCentral()
    maven { url = uri("https://jitpack.io") }
}

dependencies {
    implementation "com.github.IzakyL.jpi-mini:jpi-mini:v0.1.0"
    // For a Minecraft mod, use com.github.IzakyL.jpi-mini:jpi-mini-mc:v0.1.0 instead.
}
```

### Local development

Use JDK 21 with the included Gradle 8.14.5 wrapper:

```sh
./gradlew build
./gradlew publishToMavenLocal
```

Both modules compile with `options.release = 17`; the build itself uses JDK 21. The Java 17 port uses
cached daemon worker threads in place of virtual threads, so many simultaneous requests consume more
platform threads.

| Artifact | Use | Output |
| --- | --- | --- |
| `io.github.izakyl:jpi-mini:0.1.0` | Plain Java applications | `core/build/libs/jpi-mini-0.1.0.jar` |
| `io.github.izakyl:jpi-mini-mc:0.1.0` | Minecraft mods or a standalone mod in `mods/` | `minecraft/build/libs/jpi-mini-mc-0.1.0.jar` |

Each module also produces sources and Javadoc jars. Root `check` runs both modules' tests and `checkStyle`,
including the compile-only loader stubs. Default tests use local fake HTTP servers without contacting an LLM
provider. The explicit DeepSeek live check is skipped unless its environment key is set. Both the
local build and JitPack use `publishToMavenLocal` to stage the artifacts.

For local development, add this to the consuming project's Gradle build:

```groovy
repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    implementation "io.github.izakyl:jpi-mini:0.1.0"
}
```

Gson 2.10.1 is the only runtime dependency and is exposed as an API dependency.
There is no `module-info.java`, keeping classpath usage straightforward without requiring consumers
to configure Gson on the module path. The jar declares the stable automatic module name
`io.github.izakyl.jpi` for consumers that load automatic modules.

## Stream robustness

`StreamOptions.builder()` accepts `maxRetries` (default 0), `maxRetryDelayMs` (default 60000;
0 removes the server-delay limit), `requestTimeoutMs` and `idleTimeoutMs` (both default 0, disabled).
The request timeout covers connection through the first response byte; the idle timeout covers reads
between response bytes. Retry delays can be interrupted with the `CancellationToken`. Model and option
headers are merged case-insensitively, with option values taking precedence over model values.
Built-in provider endpoints require HTTPS or loopback HTTP. Injected HTTP clients must disable automatic redirects.

`ContextOverflow.isContextOverflow(message, contextWindow)` detects provider error messages and the
usage-based overflow cases from pi. The overload without a context window checks error messages only.

## Using from a Minecraft mod

**jpi-mini for Minecraft** (`jpimini`) is one universal jar with metadata for Fabric, Quilt, Forge and
NeoForge. It bundles the core classes without relocation; Gson stays external because Minecraft provides
it. Depend on **only `jpi-mini-mc`** in a Minecraft mod; adding the core artifact separately duplicates its
classes. The wrapper uses loader APIs for the game directory. Loader references compile against
stubs that stay out of the jar. Fabric and Quilt need no entrypoint. `JpiMini` also works in plain Java.

For NeoForge with [ModDevGradle](https://docs.neoforged.net/toolchain/docs/plugins/mdg/#jar-in-jar),
bundle the mod with `jarJar`. This example uses the Maven Local development coordinates.
For JitPack, use `jarJar(implementation("com.github.IzakyL.jpi-mini:jpi-mini-mc:v0.1.0"))`
with the same Gson exclusion.

```groovy
dependencies {
    jarJar(implementation("io.github.izakyl:jpi-mini-mc")) {
        version {
            strictly "[0.1.0,0.2.0)"
            prefer "0.1.0"
        }
        exclude group: "com.google.code.gson", module: "gson"
    }
}
```

Its `neoforge.mods.toml` makes the dependency discoverable in development runs too.
For Fabric Loom, bundle it with `include`. Use the JitPack Minecraft coordinate in both declarations for
the public release.

```groovy
dependencies {
    modImplementation("io.github.izakyl:jpi-mini-mc:0.1.0") {
        transitive = false
    }
    include("io.github.izakyl:jpi-mini-mc:0.1.0") {
        transitive = false
    }
}
```

Declare `jpimini` as a dependency in your own loader metadata as well (for Fabric,
`"depends": {"jpimini": ">=0.1.0"}`; for Forge/NeoForge, a required dependency with a compatible version
range). The smoke suite verifies one shared copy when two Fabric hosts nest the same jar.
Forge nesting and NeoForge `jarJar` range negotiation have no smoke coverage. Avoid shading the
core classes into your mod or installing multiple standalone versions in `mods/`.

```java
import io.github.izakyl.jpi.ai.StreamOptions;
import io.github.izakyl.jpi.auth.Credential;
import io.github.izakyl.jpi.auth.StoreApiKeyResolver;
import io.github.izakyl.jpi.mc.JpiMini;

// Save a key obtained from the user, on a suitable background thread (file I/O).
JpiMini.credentials().put("openai", new Credential.ApiKey(apiKey));
var options = StreamOptions.builder()
        .apiKeyResolver(new StoreApiKeyResolver(JpiMini.credentials()))
        .build();
var stream = JpiMini.providers().stream(model, context, options);
String libraryVersion = JpiMini.version();
```

Both shared services initialize lazily and are thread-safe. The registry includes OpenAI Chat Completions
(including DeepSeek), OpenAI Responses, Anthropic Messages, and Google Generative AI.
`register` rejects existing API ids. Use `replace` for intentional overrides on a registry you control.
Pass a `StoreApiKeyResolver` in each call's options to use shared credentials. Marshal game access from
callbacks and tools onto the appropriate Minecraft executor in the consuming mod.

### Verified loaders and versions

The jar has open Minecraft/loader ranges and requires Java 17 or newer. Minecraft itself still determines
the JVM it needs: 1.18–1.20.4 normally use Java 17, and 1.20.5+ uses Java 21. Open metadata is a compatibility
intent, not proof that every historical or future loader works; older loader generations and Gson versions
can impose additional limits.

Real dedicated-server smoke results (2026-10-25; Java 17 for 1.20.1, Java 21 for 1.21.1):

| Loader | Minecraft | Loader version | Result |
| --- | --- | --- | --- |
| NeoForge | 1.21.1 | 21.1.233 | Passed: constructor, `Done`, clean stop |
| Fabric | 1.21.1 | 0.20.1 | Passed: mod discovery, `Done`, clean stop |
| Forge | 1.20.1 | 47.4.28 | Passed: constructor, `Done`, clean stop |
| Fabric | 1.20.1 | 0.20.1 | Passed: mod discovery, `Done`, clean stop |
| Fabric, two hosts nesting the same jar | 1.21.1 | 0.20.1 | Passed: one loaded copy, shared services, `Done` |
| Quilt | — | — | Metadata included; no real-server smoke run |
| NeoForge | 1.20.1–1.20.4 | — | Legacy `mods.toml` included; no real-server smoke run |

Forge 1.20.1 expects pack metadata, so the jar includes an empty `pack.mcmeta` with an open supported-format
range. There are no game assets, mixins, access wideners or version-specific hooks.

Run all smoke targets (including the two-host Fabric jar-in-jar case):

```sh
./gradlew :minecraft:jar
scripts/smoke-load.sh
# Or just selected targets:
scripts/smoke-load.sh forge-1.20.1 fabric-jij-1.21.1
```

The Bash script uses curl, Java and standard Unix utilities, with no Gradle loader plugin, Python or jq.
It installs servers in `build/smoke/<target>/`, caches downloads/libraries in `build/smoke/cache/`, writes
`eula=true` under the accepted Minecraft EULA, binds loopback on an ephemeral port, waits for `Done`, verifies
mod discovery (and Forge/NeoForge constructor execution), sends `stop`, and checks a clean exit. Review
`console.log`, `install.log`, `java-version.txt`, `evidence.log` and `build/smoke/results.tsv`.
`JAVA`/`JAVA_HOME` selects the default JVM; optionally set `JAVA_17=/path/to/java17` for the 1.20.1 targets.
`SMOKE_TIMEOUT` sets the startup timeout in seconds (default 240), and `JPI_MC_JAR` overrides the jar path.
The two-host case uses a Java source launcher helper and its built-in compiler to assemble nested jars.
Its smoke-only Fabric entrypoint checks the loader's mod registry contains exactly one `jpimini` and calls
`JpiMini` to verify shared services. Fabric prints a dependency-tree reference under each host even though
only one copy is loaded. The probe's loader API references are confined to the generated fixture jars.
An initial Fabric download timeout was resolved by reusing the cached vanilla server jar; all final targets
passed, with no `jpimini` load errors. Quilt and historical/future versions outside this table remain unverified.

### Shared credentials

The Minecraft store is `<gameDir>/config/jpi-mini/credentials.json`. The wrapper reads the loader's game
directory and falls back to `user.dir` when the loader path is unavailable. Set
`-Djpimini.gameDir=/path/to/instance` before the first call to `JpiMini.credentials()` to override it.
The file is created on the first write.

Plain Java applications can use `new CredentialStore(path)`. Keys are arbitrary nonblank strings.
Use a namespace such as `modid.something` for custom entries in a shared store. The default store
resolvers use the model's provider id as their key. `get(key)` returns an `Optional`,
`put(key, credential)` persists a change, `remove(key)` deletes it, and `keys()` returns an immutable set.
jpi-mini supports only API-key authentication. Use `new Credential.ApiKey(apiKey)` to store a key.
`StoreApiKeyResolver` and `StoreCredentialResolver` read the model's provider key on each request
and return null when it is missing. Older sign-in entries are ignored and dropped on the next write.

Writes use a temporary file and atomic replacement where supported (ordinary replacement otherwise).
The store applies POSIX mode `600` or an owner-only ACL where supported. Missing files read as empty.
Malformed JSON or invalid records are moved to a sibling `credentials.json.corrupt-*` backup with
the same permissions before starting empty. Access across store instances sharing a normalized path
is synchronized within one JVM; separate processes must coordinate their writes externally.

**Security.** Credentials and recovery backups are plaintext. Filesystems without POSIX permissions or
ACLs rely on their normal access controls. Never log keys,
tokens or the file contents. All mods in the same JVM can access shared credentials. On shared servers,
prefer client-side use and keep credentials out of source control and distributed modpacks.

## Quick usage

```java
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.izakyl.jpi.agent.Agent;
import io.github.izakyl.jpi.agent.AgentEvent;
import io.github.izakyl.jpi.agent.AgentTool;
import io.github.izakyl.jpi.agent.AgentToolResult;
import io.github.izakyl.jpi.ai.AssistantMessage;
import io.github.izakyl.jpi.ai.AssistantMessageEvent;
import io.github.izakyl.jpi.ai.CancellationToken;
import io.github.izakyl.jpi.ai.Model;
import io.github.izakyl.jpi.ai.StreamOptions;
import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;

public final class Example {

    public static Agent createAgent(String apiKey) {
        var model = Model.builder("your-model-id", "https://api.openai.com/v1")
                .name("My chat model")
                .provider("openai")
                .contextWindow(128_000)
                .maxTokens(4096)
                .build();

        AgentTool clock = new AgentTool() {

            @Override
            public String name() {
                return "clock";
            }

            @Override
            public String label() {
                return "Clock";
            }

            @Override
            public String description() {
                return "Read the current UTC instant";
            }

            @Override
            public JsonObject parameters() {
                return JsonParser.parseString("{\"type\":\"object\",\"properties\":{}}").getAsJsonObject();
            }

            @Override
            public AgentToolResult execute(String id, JsonObject args, CancellationToken cancel,
                                           Consumer<AgentToolResult> onUpdate) {
                cancel.throwIfCancelled();
                return AgentToolResult.text(Instant.now().toString());
            }
        };
        return new Agent(model, "Use the clock tool for the current time.", List.of(clock),
                StreamOptions.builder().apiKey(apiKey).build());
    }

    public static void ask(String apiKey) {
        var agent = createAgent(apiKey);
        agent.subscribe(event -> {
            if (event instanceof AgentEvent.MessageUpdate update
                    && update.assistantMessageEvent() instanceof AssistantMessageEvent.Delta delta
                    && delta.kind() == AssistantMessageEvent.Kind.TEXT) {
                System.out.print(delta.delta());
            }
        });
        agent.prompt("What time is it?").thenAccept(newMessages -> {
            var last = (AssistantMessage) newMessages.get(newMessages.size() - 1);
            System.out.println("\nFinished: " + last.stopReason());
        });
        // Call agent.abort() to cancel, or agent.waitForIdle() to await settlement.
    }
}
```

Callbacks and tool execution run on library workers. Marshal access to thread-confined resources onto
an appropriate executor and make tools cooperate with `CancellationToken`.
A blocking tool that ignores cancellation delays abort.
Do not block a callback on its own stream's `result()`, agent's `prompt()` or `waitForIdle()`.

For a model call without an agent:

```java
import io.github.izakyl.jpi.ai.AssistantMessageEvent;
import io.github.izakyl.jpi.ai.CancellationToken;
import io.github.izakyl.jpi.ai.Context;
import io.github.izakyl.jpi.ai.ProviderRegistry;
import io.github.izakyl.jpi.ai.StreamOptions;
import io.github.izakyl.jpi.ai.UserMessage;
import java.util.List;

var providers = ProviderRegistry.defaults();
var cancellation = new CancellationToken();
var stream = providers.stream(model,
        new Context("Be concise.", List.of(new UserMessage("Hello"))),
        StreamOptions.builder().apiKey(apiKey).cancellation(cancellation).build());
stream.subscribe(event -> {
    if (event instanceof AssistantMessageEvent.Delta delta) {
        System.out.println(delta.type() + ": " + delta.delta());
        // For toolcall_delta, event.message().toolCalls() contains parsed partial arguments.
    }
});
var finalMessage = stream.result().join(); // On a suitable background thread.
```

`model` and `apiKey` above are caller-supplied. The base URL is the API root (`.../v1`), to which
`/chat/completions` is appended. API keys are supplied per call, or via `StreamOptions.apiKeyResolver`
(resolved for every model request). Environment lookup is opt-in through `EnvApiKeyResolver`. Credential persistence is opt-in through the `.auth` package.
A missing key is permitted for unauthenticated local endpoints. Headers merge case-insensitively:
defaults and key authorization, then model headers, then call headers.

## DeepSeek

`Model.deepseek("deepseek-v4-flash")` uses `https://api.deepseek.com` with Chat Completions,
`OpenAiCompat.deepseek()` and thinking enabled (`{"thinking":{"type":"enabled"}}`). Supply your own
key through `StreamOptions` or a resolver under provider `deepseek`. The preset uses `max_tokens`,
system messages, no `store`, and omits automatic `tool_choice`. `StreamOptions.builder().toolChoice(...)`
accepts `auto`, `none`, `required`, or a function name to force; explicit choices are still sent.
Assistant history always contains `reasoning_content`, including the original reasoning for tool calls,
and tool-call assistant turns contain `content` (empty when necessary). Special tokens and DSML
function-call markup leaked into response text are filtered across SSE chunk boundaries.

DeepSeek compatibility is inferred from the provider or URL. For other customized endpoints, set
`.compat(...)` explicitly. Compatibility settings and `.extraBody(JsonObject)` use defensive copies.
The optional live test is skipped without `JPI_LIVE_DEEPSEEK_KEY`:

```sh
./gradlew :core:test --tests '*LiveDeepSeekCheck'
```

It runs two model turns with one local tool and reports fixed, non-secret transcript facts.

## Public API

Use builders and factories to construct `StreamOptions`, `Model`, `AssistantMessage`, and
`ToolResultMessage`. Their record components expose values for reading, and canonical constructor
signatures can change as fields are added. Models built with `Model.builder` accept text by default.
For an image-capable model, specify `.input(List.of("text", "image"))`. The `ai.internal` package
is unsupported.

| API | Main entry points |
| --- | --- |
| `CredentialStore`, `Credential`, `StoreApiKeyResolver`, `CredentialResolver`, `StoreCredentialResolver` | File-backed credentials and per-request resolution |
| `Model`, `OpenAiCompat`, `StreamOptions` | `builder(...)`, model metadata, compatibility settings, credentials and cancellation |
| `Message` | Sealed `UserMessage`, `AssistantMessage`, `ToolResultMessage`; assistant `text()` and `toolCalls()` |
| `ContentBlock` | Sealed `Text`, `Thinking`, `ToolCall`, `Image` records |
| `Context`, `Tool`, `Usage`, `StopReason` | Provider-neutral input, JSON Schema declarations, token/cost accounting and outcomes |
| `ProviderRegistry`, `AiProvider` | `defaults([HttpClient])`, `register(api, provider)`, `replace(api, provider)`, `unregister(api)`, `stream(model, context, options)` |
| `OpenAiCompletions`, `OpenAiResponses`, `AnthropicMessages`, `GoogleGenerativeAi` | Built-in `AiProvider` implementations with injectable JDK `HttpClient` |
| `AssistantMessageEventStream` | `subscribe(listener)`, `result()`; custom providers hold a `producer()` and return its `stream()`. The producer publishes updates with `publish(event)` and derives the terminal event with `complete(message)` |
| `AssistantMessageEvent` | `Start`, `BlockStart`, `Delta`, `BlockEnd`, `Done`, `Error`; `type()` and `message()` on every event |
| `CancellationToken`, `Subscription`, `ApiKeyResolver` | `cancel()`, `isCancelled()`, `onCancel(...)`, `throwIfCancelled()`; `close()` unsubscribes; `resolve(model)` |
| `StreamingJson`, `SchemaValidator` | `append(fragment)`, `finish()`; `validate(schema, value)`, `validateToolArguments(tool, call)` |
| `AgentTool`, `AgentToolResult` | Tool metadata, `execute(id, args, cancel, onUpdate)`; `text(...)`, `error(...)` |
| `Agent` | `prompt(String/UserMessage)`, `continueRun()`, `abort()`, `subscribe(...)`, `waitForIdle()`, `isStreaming()`, `state()` |
| `Agent` state/queues | `setModel`, `setSystemPrompt`, `setTools`, `replaceMessages` while idle; `steer`, `followUp`, `clearQueues` |
| `AgentLoop` | `agentLoop(prompts, context, config)`, `agentLoopContinue(context, config)` |
| `AgentContext`, `AgentLoopConfig`, `AgentState` | Immutable context/configuration/state snapshots; optional queue suppliers, `transformContext`, and turn limit in loop config |
| `AgentEvent`, `AgentEventStream` | Agent/turn/message/tool lifecycle records; `subscribe(...)`, `result()` |

## Semantics and supported scope

* Stream request errors, HTTP errors, SSE failures and aborts end with an `Error` event and a final
  assistant message whose stop reason is `ERROR` or `ABORTED`. `result()` resolves normally for those
  outcomes. Success ends with `Done`; stop reasons are `STOP`, `LENGTH` and `TOOL_USE`.
* Successful provider streams start with `start`; content has `text_*`, `thinking_*` and `toolcall_*`
  start/delta/end events. Final usage can arrive after `finish_reason`. Partial event messages are
  snapshots, including defensive copies of JSON; their stop reason is provisional until completion.
* Each stream retains events for ordered replay to late listeners. Callbacks are serial worker callbacks,
  including replay. Runtime exceptions from listeners are isolated. Futures settle after terminal
  delivery to current listeners; late subscriptions have their own asynchronous replay.
* Chat Completions supports text, base64 input images (including images returned by tools), thinking
  deltas, interleaved parallel tool-call streams, cached input tokens, reasoning-token breakdown,
  and optional catalog price estimates per million tokens. Reasoning tokens are already included in output.
* `OpenAiCompat` defaults target OpenAI. Explicit options select `max_tokens` vs
  `max_completion_tokens`, system vs developer role (developer is used only on reasoning models),
  store/usage/effort/finish-reason support, tool-result names, assistant separators, thinking-as-text,
  required reasoning replay, and reasoning delta fields. Builder defaults infer supported compatibility settings from the provider and base URL;
  an explicit `compat` overrides those defaults.
  For a generic endpoint, explicitly disable unsupported flags and choose its token field, for example:

  ```java
  var compat = OpenAiCompat.builder().maxTokensField("max_tokens")
          .supportsDeveloperRole(false).supportsStore(false).supportsReasoningEffort(false).build();
  // Supply with Model.builder(...).compat(compat).
  ```

* `StreamingJson` incrementally reads tool arguments and exposes incomplete strings, objects, arrays,
  number prefixes and literals. Final JSON is parsed strictly as an object; malformed final input is
  marked with `ToolCall.argumentsError` and cannot execute. Unlike pi's optimized parser, snapshots
  copy the full preview; very large arguments with tiny chunks can cost quadratic copying time.
* The schema subset is `type` (including arrays of type names), `properties`, `required`, `enum`,
  single-schema `items`, and boolean/schema `additionalProperties`. `validate` checks strictly;
  `validateToolArguments` validates a copy after pi-style primitive coercion and optional-null removal.
  `title` and `description` are annotations. The supported public `SchemaValidator.validate` method returns
  an immutable list of path-qualified schema or value problems, empty on success. Tools reject unsupported
  keywords such as `$ref`, compositions, bounds and formats when declared.
* The agent emits `agent_start/end`, `turn_start/end`, `message_start/update/end`, and
  `tool_execution_start/update/end`. Tools execute sequentially; provider parsing supports simultaneous
  calls. Tool errors, unknown tools and invalid arguments become `ToolResultMessage(isError=true)`
  and are sent back to the model. Calls from a `LENGTH` response are rejected as truncated. Each run allows
  up to 32 model requests by default. Set `AgentLoopConfig.maxTurns` when using the low-level loop to
  configure the limit. A run that needs another request after reaching the limit ends with an error.
* The optional `transformContext` hook in `AgentLoopConfig` and `Agent` receives an immutable transcript
  snapshot before each model request. The returned list is used for the request. The agent retains
  the original transcript.
* Tool updates are scoped to the active execution. Tool details stay in the transcript/events and
  are not sent to the model. Assistant thinking is replayed using the supported reasoning field.
* Steering is consumed one message per turn boundary; follow-ups are consumed one at a time when the
  agent would otherwise stop. Abort/error stops the run and leaves unconsumed queues intact.
  `clearQueues()` removes them. Abort releases the agent's wait for a returned provider stream.
  Tools already executing must cooperate with cancellation.
* `Agent.prompt()` and low-level loop futures return messages produced by that run, including its
  prompt, rather than the old transcript. `state()` holds the full transcript and current partial message.
  Concurrent prompts and state setters during a run throw `IllegalStateException`; use queues to steer.
  Invalid continuation contexts throw `IllegalArgumentException` before a run starts.
  These are caller-usage errors, separate from the provider stream's resolved-error contract.

## Port boundaries and roadmap

The port covers Chat Completions SSE with DeepSeek thinking compatibility, OpenAI Responses,
Anthropic Messages, and Google Generative AI. The providers share retry and timeout handling.
The agent supports sequential tool execution, continuation, steering and follow-up queues,
and a per-request context transform.

Other provider APIs, WebSockets, provider-specific routing and cache controls, grammar
tools, compaction, parallel tool execution, and durable replay remain outside the current scope.
Consuming applications own their UI and configuration.

An optional `EnvApiKeyResolver` reads provider API keys from environment variables. Pass it explicitly
with `StreamOptions.builder().apiKeyResolver(new EnvApiKeyResolver())`; it does not alter existing
credential resolution unless selected by the caller.

The offline model catalog can be regenerated with Node 24 using `node scripts/generate-models.mjs`.
The generator downloads `https://models.dev/api.json` and writes the bundled JSON resource.
Only providers using implemented APIs are included. For a proxy, Node can be started with
`NODE_USE_ENV_PROXY=1`. Models can be queried with `Models.get(provider, id)` and
`Models.of(provider)`; `Providers.all()` lists the included providers.
