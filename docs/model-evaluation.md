# Model evaluation (Phase 7)

The evaluation measures how well a language model researches with Argos' MCP tools. A model from a local
Ollama (or any OpenAI-compatible endpoint) drives Argos through a fixed task set; Argos runs as deployed:
real Camoufox, the network boundary, and MCP over Streamable HTTP, called by a real MCP client.

Nothing leaves the machine for the regression set: the pages, the search engine and the expected answers come
from a small fictional web served on loopback (`FixtureWorld`). The facts are invented (the "Kestrel Valley
Observatory"), so a model cannot answer from its training data. Every site has its own domain under the reserved
`.test` top-level domain (`www.kestrel-observatory.test`, `stargazer-forum.test`, `kestrelsearch.test`, ...),
resolved to loopback for Argos' network policy and proxy only. With plain `http://127.0.0.1:port` addresses the
first Windows run showed models refusing to open local addresses at all, which measured an assumed restriction
instead of the tools.

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

The command exits non-zero when the evaluation is incomplete: a model that cannot be loaded (e.g. CUDA out of
memory) skips **every** profile of that model, and runs that end with a model error are marked invalid. Both are
listed at the top of `summary.md` as INCOMPLETE and never counted as 0 % success.

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
| `num-gpu` | Ollama decides | Ollama `num_gpu`: model layers on the GPU, the rest on the CPU (e.g. `16` for gpt-oss:20b on a 16 GB card with 8K context) |
| `prompt` | `authorized` | `authorized` adds one neutral sentence that every address in the question may be opened; `plain` leaves it out (A/B) |
| `part-chars` | `12000` | `web_read` part size (Argos default), for A/B runs against small context lengths |
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

# gpt-oss:20b partly on the CPU when the GPU memory is too small (CUDA out of memory)
.\gradlew.bat evalModels "-Peval.num-gpu=16"

# Three repetitions with 16K context, only two tasks
.\gradlew.bat evalModels "-Peval.repeat=3" "-Peval.num-ctx=16384" "-Peval.tasks=dynamic-page,simple-form"

# Optional large reference model over an OpenAI-compatible API (costs money; the key stays out of the report)
$env:ARGOS_EVAL_API_KEY = "..."
.\gradlew.bat evalModels "-Peval.api=openai" "-Peval.base-url=https://openrouter.ai/api/v1" "-Peval.runs=openai/gpt-oss-120b@compact"
```

## Tasks

| Id | Checks | Expected answer | Distractor |
|---|---|---|---|
| `find-site` | Search, pick the official site among fan club and hotel | `www.kestrel-observatory.test` (home page) | fan club, hotel |
| `search-pick` | Search, choose the official report over a forum guess | 212 clear nights | 150 (forum) |
| `follow-link` | Start page, find and follow the right link, read a table | Halvard reflector | – |
| `dynamic-page` | Page whose content arrives by a slow XHR (1.5 s) | Dr. Ilse Marrow | – |
| `search-retry` | First search answers HTTP 503; retry or rephrase | Saturday 18:00–23:30 | hotel 12:00–22:00 |
| `broken-link` | Official page answers HTTP 500; use the mirror | Ravenmoor | – |
| `simple-form` | Fill a ticket form and submit | CONFIRMED, seat F12 | – |
| `long-page` | Answer in the last part of a long page (`web_find`, or `web_read part=N`) | Margit Solberg | – |
| `live-rfc-title` | Real search engines and sites | HTTP Semantics | – |
| `live-rfc-editor` | Open and read a real page | Fielding | – |
| `live-jep` | Real search, pick, read | JEP 444 | – |

`simple-form` cannot be solved with the six compact tools (there is no tool to submit a form). It still runs in
`compact`, but is reported as "not solvable" and does not count towards the success rate. The live tasks depend on pages and search engines outside
our control: use them as a plausibility check, never as a regression gate.

The prompts are plain user questions and never name a tool; choosing the tool is part of the test.

## What is measured

| Field | Meaning |
|---|---|
| `successful` | The model answered, every expected pattern occurs, no distractor occurs, and at least one tool call worked |
| `applicable` | The profile's tools can solve the task (false for `simple-form` with `compact`) |
| `valid` | False when the run ended with a model or endpoint error; such runs are reported apart |
| `toolCallCount`, `toolCalls` | Every call with arguments, duration, size and a preview of the answer |
| `errors` | Tool errors, unknown tool names, arguments that are not a JSON object |
| `recoveryActions` | Calls made right after a setback (an error, `No results.`, an HTTP error page) |
| `recovered` | The run hit a setback and still answered correctly |
| `textualToolCalls` | Calls the model wrote into its text instead of the tool-call field (recovered by the harness, counted) |
| `promptTokens`, `completionTokens` | Sums over all turns as reported by the server |
| `maxPromptTokens`, `estimatedPromptTokens`, `contextFull` | Largest prompt the server reported and the harness' own estimate (≈ 3.5 characters per token); `contextFull` when either reaches 95 % of `num-ctx`. Ollama cuts old messages to fit and then reports the cut size, so the estimate is the reliable signal |
| `elapsedMillis`, `modelMillis`, `toolMillis` | Wall clock, model time without loading, time inside Argos |
| `loadMillis` | Model loading during the task (Ollama `load_duration`) |
| `stopReason` | `answered`, `max_steps`, `empty_answer`, `model_error`, `time_limit` |

Model loading is measured separately: before the first task of each model a warm-up request loads it, and its
load time is reported in its own table. Requests ask Ollama to keep the model loaded (`keep_alive` 15 minutes), so
the task times contain no loading unless the server had to evict the model. When the evaluation moves on to the
next model, the previous one is unloaded (`keep_alive` 0), so two models never compete for GPU memory.

A model's separate reasoning (Ollama `thinking`, e.g. gpt-oss) is kept in the transcript and sent back with its
tool-calling turns, as gpt-oss expects.

The chat loop is deliberately plain (system prompt, question, tools until an answer, no hidden hints, retries or
nudges), so the result measures the model with Argos' tools, not the harness. Each task gets a fresh MCP session
and web session; the browser stays open for the whole evaluation.

## Self-test without a model

`EvalHarnessCamoufoxTest` (`-PtestCamoufox=true`) runs the whole path with a scripted reference agent instead of a
model and requires all eight fixture tasks to succeed, including the recovery tasks. It proves that every task
is solvable through the published tools; a failing model run is then the model's or the tool design's limit,
not a broken fixture.

## Findings and changes after the first Windows run (PR #13)

Angelo's run on `808e08a` (one repetition each, num_ctx 8192): gemma4:e2b@compact 2/8, gpt-oss:20b@compact 5/8,
gpt-oss:20b@expert 7/8. Changes from it:

- **Incomplete runs were reported as finished.** gpt-oss:20b did not load (CUDA out of memory); the harness skipped
  only its first profile, ran `expert` into eight model errors, and exited 0. Now every profile of an unloadable
  model is skipped, model errors are invalid runs, the report says INCOMPLETE and the command fails.
- **GPU memory:** `eval.num-gpu` sets Ollama's `num_gpu`, and the previous model is unloaded before the next.
- **Local addresses:** models refused `http://127.0.0.1:port` addresses without calling a tool. The fixture sites
  now have `.test` domains, and the default system prompt says neutrally that every address in the question may be
  opened (`-Peval.prompt=plain` for the A/B comparison without it).
- **web_read after browser_navigate** (expert) answered "No page is open yet". `web_read` and `web_links` now
  follow the page the browser shows, whichever tool opened it (never the search provider's page); fixed on the
  integration branch (#12).
- **dynamic-page:** `web_open` returned "Loading events…" while the page's data request was still open, and both
  compact runs failed there. `web_open` and `web_read` now read such a page once more when its open requests have
  finished (at most 8 s, protocol events only, no page script), also on the integration branch (#12). The
  self-test solves it with a single `web_open`.
- **long-page:** reading all three parts of the archive takes about 8,600 tokens with the questions and tools, more
  than `num_ctx` 8192, so Ollama drops earlier messages, possibly the question itself. This matches the
  `empty_answer` endings. Smaller parts do not help when a model reads every part; options are 16K context
  (`-Peval.num-ctx=16384`), or a way to search inside a long page instead of reading it whole (a tool design question,
  not changed yet).

Next step: three repetitions per configuration with identical settings, e.g.
`.\gradlew.bat evalModels "-Peval.repeat=3" "-Peval.num-gpu=16"`.

## Three repetitions on `adf049d`

Angelo's run (`repeat=3`, `num_gpu` 16, 8K): gemma4:e2b@compact 15/21, gpt-oss:20b@compact 15/21,
gpt-oss:20b@expert 18/24. From the `broken-link` and `find-site` transcripts:

- **find-site in expert was judged wrong but answered correctly.** gpt-oss wrote the address with a non-breaking
  hyphen (`kestrel\u2011observatory`). The judge now reads U+2010/U+2011 as `-` and no-break spaces as spaces; with
  it, expert solves 21/24.
- **broken-link in compact is a real failure.** Both models see the official page's "500 Internal Server Error"
  title but never open the mirror (search result 2). gemma4:e2b searches again, gets the same list and gives up or
  repeats the snippet ("Kestrel Valley dark-sky zone"); gpt-oss:20b guesses addresses on the official site and the
  archive, until about 10,000 estimated tokens end it with `empty_answer`. In expert it reaches the mirror after
  eight calls.
- A guessed `https://` address on the plain-HTTP fixture port waits the full 45 s navigation timeout.

## After the three repetitions: `web_find` and the HTTP status line

Approved by Angelo after the run above; measured apart from it (the numbers above stay the baseline, expert 21/24).

- **`web_find`** (PR #15, compact and expert): searches the open page's text and returns the match count and
  short snippets with their `web_read` part. The self-test solves `long-page` with `web_open` + `web_find` at an
  estimated 4,400 prompt tokens, instead of about 8,600 for reading every part.
- **HTTP status line** (PR #16): `web_open`, `web_follow`, `web_read` and `web_back` add
  `HTTP error: 500 Internal Server Error — the requested content may not be available on this page.` when the
  main document answers 400 or higher. The self-test requires it on `broken-link`.

Rerun for the comparison:
`.\gradlew.bat evalModels "-Peval.repeat=3" "-Peval.num-gpu=16" "-Peval.tasks=broken-link,long-page"`

## Observations from the self-test

- `web_open` shows an HTTP 500 error page as ordinary page text without a status line. gpt-oss recovered in
  `broken-link` in both profiles; whether small models recognise it is measured by `broken-link`.
