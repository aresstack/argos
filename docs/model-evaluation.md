# Model evaluation (Phase 7)

The evaluation measures how well a language model researches with Argos' MCP tools. A model from a local
Ollama (or any OpenAI-compatible endpoint) drives Argos through a fixed task set; Argos runs as deployed:
real Camoufox, the network boundary, and MCP over Streamable HTTP, called by a real MCP client.

Nothing leaves the machine for the regression set: the pages, the search engine and the expected answers come
from a small fictional web served on loopback (`FixtureWorld`). The facts are invented (the "Kestrel Valley
Observatory"), so a model cannot answer from its training data.

## One command

Prerequisites are the same as for `-PtestCamoufox=true`: `ARGOS_CAMOUFOX_EXECUTABLE` and
`ARGOS_CAMOUFOX_LAUNCH_OPTIONS` (environment or `~/.argos/argos.properties`), and Ollama with the models pulled.

```powershell
.\gradlew.bat evalModels
```

Without further settings this runs the Phase 7 plan against `http://127.0.0.1:11434/api/chat`:

1. `gemma4:e2b` with the `compact` profile,
2. `gpt-oss:20b` with the same `compact` profile and the same tasks,
3. `gpt-oss:20b` with the `expert` profile (influence of tool complexity).

In IntelliJ: Gradle tool window, `argos > Tasks > verification > evalModels`, or a Gradle run configuration with
`evalModels -Peval.runs=...`. No GitHub runner, cloud service or API key is needed.

The report goes to `build/eval/<yyyyMMdd-HHmmss>/`:

| File | Content |
|---|---|
| `summary.md` | Comparison per model and profile, task matrix, per-run table, every failure with its tool calls |
| `results.json` | Settings, published tools per profile, warm-ups, totals per configuration, all runs |
| `results.jsonl` | One line per run, written as soon as the run ends (an aborted evaluation keeps its data) |
| `transcripts/` | Full conversation of every run (messages, tool calls, tool answers, fixture requests) |

## Settings

Every setting is `-Peval.<name>=...` or the environment variable `ARGOS_EVAL_<NAME>` (dashes become underscores).

| Setting | Default | Meaning |
|---|---|---|
| `runs` | `gemma4:e2b@compact,gpt-oss:20b@compact,gpt-oss:20b@expert` | `model@profile` pairs, in order |
| `model`, `profile` | – | Alternative to `runs`: every model × every profile (both comma-separated, profile default `compact`) |
| `tasks` | `fixtures` | `fixtures` (8 deterministic tasks), `live` (3 real-web tasks), `all`, or task ids |
| `repeat` | `1` | Runs of the whole task set per configuration |
| `api` | `ollama` | `ollama` (native `/api/chat`) or `openai` (`/chat/completions`) |
| `base-url` | `http://127.0.0.1:11434` | For `openai` including the version path, e.g. `https://openrouter.ai/api/v1` |
| `api-key` | – | Only for `openai`; never written to the report |
| `num-ctx` | `8192` | Ollama context length, sent with every request (the global Ollama setting is not used) |
| `temperature` | `0.0` | Sent explicitly |
| `seed` | `42` | Sent explicitly |
| `max-output-tokens` | `4096` | Ollama `num_predict` / OpenAI `max_tokens` per answer |
| `think` | server default | Ollama `think`: `true`, `false` or a level (`low`, `medium`, `high` for gpt-oss) |
| `reasoning-effort` | server default | OpenAI-compatible `reasoning_effort` |
| `max-turns` | `15` | Model turns per task before it counts as `max_steps` |
| `task-time-limit-s` | `600` | Wall clock per task |
| `request-timeout-s` | `300` | One model request |
| `headless` | `true` | `false` shows the Camoufox window |
| `out` | `build/eval` | Report directory |

Profiles: `legacy`, `compact`, `expert`, plus the candidate `compact-interact` (compact + `web_controls`,
`web_click`, `web_fill`). Those three tools join the published compact profile only after small models handle them;
`compact-interact` exists to measure exactly that. The published profiles are not changed by the evaluation.

Examples:

```powershell
# Optional third local model, same tasks and tools
.\gradlew.bat evalModels "-Peval.runs=qwen2.5-coder:14b@compact"

# Do small models cope with the form tools?
.\gradlew.bat evalModels "-Peval.runs=gemma4:e2b@compact-interact,gpt-oss:20b@compact-interact"

# Three repetitions with 16K context, only two tasks
.\gradlew.bat evalModels "-Peval.repeat=3" "-Peval.num-ctx=16384" "-Peval.tasks=dynamic-page,simple-form"

# Optional large reference model over an OpenAI-compatible API (costs money; the key stays out of the report)
$env:ARGOS_EVAL_API_KEY = "..."
.\gradlew.bat evalModels "-Peval.api=openai" "-Peval.base-url=https://openrouter.ai/api/v1" "-Peval.runs=openai/gpt-oss-120b@compact"
```

## Tasks

| Id | Checks | Expected answer | Distractor |
|---|---|---|---|
| `find-site` | Search, pick the official site among fan club and hotel | URL ending in `/kvo/` | fan club, hotel URLs |
| `search-pick` | Search, choose the official report over a forum guess | 212 clear nights | 150 (forum) |
| `follow-link` | Start page, find and follow the right link, read a table | Halvard reflector | – |
| `dynamic-page` | Page whose content arrives by a slow XHR (1.5 s) | Dr. Ilse Marrow | – |
| `search-retry` | First search answers HTTP 503; retry or rephrase | Saturday 18:00–23:30 | hotel 12:00–22:00 |
| `broken-link` | Official page answers HTTP 500; use the mirror | Ravenmoor | – |
| `simple-form` | Fill a ticket form and submit | CONFIRMED, seat F12 | – |
| `long-page` | Answer in the last part of a long page (`web_read part=N`) | Margit Solberg | – |
| `live-rfc-title` | Real search engines and sites | HTTP Semantics | – |
| `live-rfc-editor` | Open and read a real page | Fielding | – |
| `live-jep` | Real search, pick, read | JEP 444 | – |

`simple-form` cannot be solved with the six compact tools (there is no tool to submit a form); its result in
`compact` is expected to fail and documents that gap. The live tasks depend on pages and search engines outside
our control: use them as a plausibility check, never as a regression gate.

The prompts are plain user questions and never name a tool; choosing the tool is part of the test.

## What is measured

| Field | Meaning |
|---|---|
| `successful` | The model answered, every expected pattern occurs, no distractor occurs, and at least one tool call worked |
| `toolCallCount`, `toolCalls` | Every call with arguments, duration, size and a preview of the answer |
| `errors` | Tool errors, unknown tool names, arguments that are not a JSON object |
| `recoveryActions` | Calls made right after a setback (an error, `No results.`, an HTTP error page) |
| `recovered` | The run hit a setback and still answered correctly |
| `textualToolCalls` | Calls the model wrote into its text instead of the tool-call field (recovered by the harness, counted) |
| `promptTokens`, `completionTokens` | Sums over all turns as reported by the server |
| `maxPromptTokens`, `contextFull` | Largest single prompt; `contextFull` at ≥ 95 % of `num-ctx` (Ollama starts cutting old messages) |
| `elapsedMillis`, `modelMillis`, `toolMillis` | Wall clock, model time without loading, time inside Argos |
| `loadMillis` | Model loading during the task (Ollama `load_duration`) |
| `stopReason` | `answered`, `max_steps`, `empty_answer`, `model_error`, `time_limit` |

Model loading is measured separately: before the first task of each model a warm-up request loads it, and its
load time is reported in its own table. Requests ask Ollama to keep the model loaded (`keep_alive` 15 minutes), so
the task times contain no loading unless the server had to evict the model.

The chat loop is deliberately plain (system prompt, question, tools until an answer, no hidden hints, retries or
nudges), so the result measures the model with Argos' tools, not the harness. Each task gets a fresh MCP session
and web session; the browser stays open for the whole evaluation.

## Self-test without a model

`EvalHarnessCamoufoxTest` (`-PtestCamoufox=true`) runs the whole path with a scripted reference agent instead of a
model and requires all eight fixture tasks to succeed, including the recovery tasks. It proves that every task
is solvable through the published tools; a failing model run is then the model's or the tool design's limit,
not a broken fixture.

## Observations from the self-test

- `web_open` on `dynamic-page` returns while the slow XHR is still open (balanced readiness tolerates in-flight
  requests), so the first answer shows "Loading events…". A second `web_read` about two seconds later has the
  content. Local models usually need longer than that for their next turn, so this rarely hurts in practice; the
  model runs will show whether `web_read` should wait for the network to settle first.
- `web_open` shows an HTTP 500 error page as ordinary page text without a status line. Whether small models
  recognise it is measured by `broken-link`.
