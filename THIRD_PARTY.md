# Third-party notices

jpi-mini is an unofficial, partial Java port of [pi](https://github.com/badlogic/pi-mono),
reference commit [`d5b3de2`](https://github.com/badlogic/pi-mono/commit/d5b3de2).
It is not affiliated with or endorsed by pi's authors.

The following source files informed the port (paths relative to the pi repository):

| pi source | jpi-mini implementation |
| --- | --- |
| `packages/ai/src/types.ts` | AI records, sealed content/message/event types, model and options |
| `packages/ai/src/api/openai-completions.ts` | `OpenAiCompletions`, request compatibility and usage accounting |
| `packages/ai/src/api/openai-responses-shared.ts` | Responses history, SSE events, reasoning and tool-call replay |
| `packages/ai/src/utils/streaming-json.ts` | `StreamingJson`, tolerant incremental argument previews |
| `packages/ai/src/utils/validation.ts` | `SchemaValidator`, supported schema subset and primitive coercion |
| `packages/ai/src/utils/event-stream.ts` | Listener streams and terminal result semantics |
| `packages/agent/src/types.ts` | Agent records, events, and executable tool interface |
| `packages/agent/src/agent-loop.ts` | `AgentLoop`, tool execution, continuation and queue semantics |
| `packages/agent/src/agent.ts` | `Agent`, state, subscriptions and prompt/abort/idle lifecycle |

Implementation paths are relative to `core/src/main/java/io/github/izakyl/jpi/`:
`ai/` contains the AI types, provider, parser and validator; `ai/internal/` contains listener streams,
strict JSON parsing and workers; `agent/` contains the agent types, loop and stateful facade;
`auth/` contains API key storage and resolution.

The implementation adapts these semantics to Java and does not bundle pi's TypeScript code
or its npm dependencies. The upstream license follows verbatim.

```text
MIT License

Copyright (c) 2025 Mario Zechner

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## models.dev model catalog

The bundled model catalog includes data from [models.dev](https://models.dev/) under the
[MIT License](https://github.com/anomalyco/models.dev/blob/dev/LICENSE).

```text
MIT License

Copyright (c) 2025 models.dev

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```
