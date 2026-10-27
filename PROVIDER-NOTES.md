# Provider API research — 26 October 2026

## Model discovery

Connections verifies entered credentials before saving. Because catalogs can be public, verification also sends a short authenticated `/chat/completions` request with a one-token output limit. It uses the saved model, an entered manual model ID, or a model from live discovery; no model ID is hardcoded. Catalog-only success never labels a key as verified. Authentication, model access, rate limits, endpoint errors, and cancellation remain distinct from successful verification. Servers without discovery can enter a manual model ID in the same sheet. There is no unchecked-save action.

The check uses a separate OkHttp client with redirects disabled and a bounded timeout, so cancelling it does not stop Chat or Work. It never copies chat history or attachments into the verification request. Credentials still use the existing Keystore-backed storage after a successful save.

## Response timing and token usage

Chat stores each response's elapsed request time and completion timestamp. Where supported, streaming requests ask for `stream_options.include_usage` and read `usage.completion_tokens`, including a final usage-only event with no choices. Servers explicitly rejecting that option are retried once without it; unknown custom endpoints keep their existing request shape. If usage is absent or a response is stopped, the displayed rate uses a rough UTF-8 length estimate and is marked `≈`. The rate is output tokens divided by the full request duration, including waiting time; it is not a GPU benchmark. UI-only model-change events are saved in history and excluded from API messages.

References: [NVIDIA streaming options](https://docs.api.nvidia.com/nim/re/reference/moonshotai-kimi-k2-6-infer), [Gemini compatibility](https://ai.google.dev/gemini-api/docs/openai), [Ollama compatibility](https://github.com/ollama/ollama/blob/main/docs/api/openai-compatibility.mdx).

The app now queries catalogs instead of shipping a fixed list or assuming one Gemini model is available. NVIDIA NIM was checked directly at `https://integrate.api.nvidia.com/v1/models`; the endpoint returned an OpenAI-shaped catalog. Catalog membership does not guarantee inference access for every key. NIM, llama.cpp, custom endpoints and Ollama use the configured base URL plus `/models`. OpenAI-style pagination is followed where advertised.

For Google's official endpoint, the app queries native `v1beta/models` using `x-goog-api-key`, follows page tokens, reads model display names and thinking metadata, and restricts results to `generateContent` models. Inference continues through Google's documented OpenAI-compatible endpoint. Known embedding, reranking and media-only IDs are excluded. No API key appears in a URL, application log, or catalog cache. Endpoint and credential changes use distinct local caches; a failed refresh keeps an existing cache visible and displays the failure. Manual IDs remain available for servers without discovery.

Google's current deprecation page distinguishes AI Studio availability from API availability. The app no longer selects Gemini 2.5 Flash by default. Models available to the user's own project come from live discovery; no newer model is presumed to be enabled on every account.

## Reasoning

The composer opens a searchable model bottom sheet with an animated, stepped reasoning control. Default omits the reasoning parameter. Known model families use these documented mappings in both Chat and Work:

- Gemini: `reasoning_effort`; the original 3/3.1 Pro families are restricted to low/high. Thinking metadata set to false disables the control.
- GPT-OSS: low/medium/high via `reasoning_effort`.
- NVIDIA Nemotron 3 Super: none/low/high via `reasoning_effort`.
- NVIDIA GLM 5.3 Flash: `chat_template_kwargs.reasoning_effort`.
- NVIDIA Qwen3: boolean `chat_template_kwargs.enable_thinking`; the UI offers Default/Off/On rather than pretending to provide three distinct effort levels.
- Ollama Qwen3 / DeepSeek R1: Default/Off/On using the documented compatibility field. GPT-OSS uses its separate effort levels.

Unknown model families keep server defaults. A catalog generally does not promise reasoning or function-calling capabilities; the app does not invent them. Support for new families can be added in `ReasoningPolicy`. Arbitrary per-model Ollama reasoning metadata is not yet queried with `/api/show`; unknown models therefore keep defaults.

## Primary sources

- [Google OpenAI compatibility, model listing and reasoning](https://ai.google.dev/gemini-api/docs/openai)
- [Google model metadata API](https://ai.google.dev/api/models)
- [Google model availability and deprecations](https://ai.google.dev/gemini-api/docs/deprecations)
- [NVIDIA hosted LLM APIs](https://docs.api.nvidia.com/nim/reference/llm-apis)
- [NVIDIA Nemotron 3 Super request parameters](https://docs.api.nvidia.com/nim/reference/nvidia-nemotron-3-super-120b-a12b-infer)
- [NVIDIA GLM 5.3 Flash reasoning](https://docs.nvidia.com/nim/large-language-models/latest/get-started/advanced/get-started-glm-5.3-flash.html)
- [NVIDIA Qwen3.5 reasoning parameter](https://docs.api.nvidia.com/nim/re/reference/qwen-qwen3-5-122b-a10b-infer)
- [Ollama OpenAI compatibility](https://docs.ollama.com/api/openai-compatibility)
- [Markwon Markdown renderer](https://github.com/noties/Markwon)

No authenticated model inference is claimed as tested. Catalog/parsing tests use fixture HTTP servers; the public NIM catalog was also fetched directly during implementation.
