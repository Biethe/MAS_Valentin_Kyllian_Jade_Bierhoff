---
doc: checklist
status: approved
---

# Build Checklist

Build mode: learn — no commits (learner's choice); `Commit:` lines are suggested messages only

## Slices

- [x] **1. The fictional mailbox runs over MCP, and a reference trace replays through it**
  Becomes usable: `./bench show reply-prof-01` replays one hand-written reference trace for a reply scenario. The replayer starts the mailbox MCP server, makes its tool calls over stdio, and the trace shows the route, the facts, the output, and the tool calls as the mailbox logged them. Your future system can talk to the same server.
  Why now: This is the riskiest piece. The MCP SDK 2.0.0 stdio API and its Jackson version are unverified, and the trace contract is what your system will plug into later. Bootstrapping is included: pom.xml, the `bench` script, and gitignoring `runs/`. Your `com/` sketch stays untouched.
  PRD ref: `prd.md > Proving the Benchmark`, `prd.md > Tracing`, `prd.md > Drafts, Never Sends`
  Spec ref: `spec.md > Stack`, `spec.md > Components` (Mailbox Server, MCP Mail Client), `spec.md > Data Model` (Scenario, Trace contract), `spec.md > File Structure`
  Build: pom.xml (Java 21, MCP SDK 2.0.0, deps copied to target/lib); `MailboxServer` with search_mail/read_mail/read_thread/create_draft, no send tool, and a tool-call log; `Replayer` that follows the trace contract (`SCENARIO`, `MAILBOX_CMD`, `TRACE_OUT`); `scenarios/university.json`; `scenarios/reply-prof-01.json` with source; `scenarios/refs/reply-prof-01.good.jsonl`; `Bench show`.
  Verify (mechanical): `mvn -q package` succeeds; the server's tool list contains exactly the 4 tools and no send tool; replaying the good reference writes a trace plus a `tools.jsonl` containing the `read_mail` and `create_draft` calls; `./bench show reply-prof-01` prints the steps in order.
  Learner check: Read `scenarios/reply-prof-01.json` and run `./bench show reply-prof-01`. Say whether the scenario format and the trace contract fit how your system will work (routes, facts, draft).
  Commit: `Add MCP fictional mailbox, trace contract and replayer`

- [x] **2. That scenario is scored layer by layer, and broken references fail where they should**
  Becomes usable: `./bench check` scores the good reference as successful, and broken references (wrong route, missing read, wrong recipient, missing fact, missing greeting, wrong language, hallucinated date) each as failed at exactly their layer. A mismatch is reported as a gap.
  Why now: Per-layer scoring is the unique kernel, and testing the test is how the benchmark is proven. Getting this right on one scenario shapes the format for the other 19.
  PRD ref: `prd.md > Run Outcomes`, `prd.md > Proving the Benchmark`, `prd.md > Output Check: Replies and New Mails` (deterministic items)
  Spec ref: `spec.md > Components` (Layer Checks, Runner and Run Store), `spec.md > Data Model` (Run result)
  Build: `Checks` for route, tools (mustRead, draft to/cc as sets, forbidden + harmful flag), facts (regex over extracted items), output (mustContain/mustNotContain, greeting, stopword language ratio, entity hallucination check); invalid outcome on a missing required step; `results.json`; `Bench check`; first failing layer marked in `show`; `ChecksTest`; first version of `./bench lint`.
  Verify (mechanical): `mvn -q package` runs `ChecksTest` and it passes; `./bench check` exits 0 with every reference matching its expected outcome; a deliberately mislabeled reference makes it exit 1 and name the gap.
  Learner check: Run `./bench check`, then `./bench show` on two broken references. Say whether each failure lands on the layer you'd blame, and whether the checks feel too strict or too loose.
  Commit: `Score scenarios per layer and prove checks with broken references`

- [x] **3. Reply, first-sender and no-matching-mail scenarios are complete**
  Becomes usable: 12 writing and ask-user scenarios (4 per kind, 2 French and 2 English) with good and broken references, adapted from WorkBench task shapes. `./bench check` proves every one, and the linter enforces coverage.
  Why now: The scoring format settled in slices 1-2, so the bulk of the scenario writing lands on stable ground. These kinds share the draft and ask-user checks.
  PRD ref: `prd.md > Scenarios`, `prd.md > Missing Information`, `prd.md > Language`, `prd.md > Tone Profiles`
  Spec ref: `spec.md > Components` (Scenario Set, Layer Checks), `spec.md > Data Model`
  Build: the remaining 11 scenarios across all 5 roles; `scenarios/tone-profiles.json`; ask-user check (question produced, no `create_draft`, the gap named); COMPOSE language default (French); full linter coverage check for these kinds; `scenarios/SOURCES.md` started.
  Verify (mechanical): `./bench lint` passes; `./bench check` exits 0 over all references; no ask-user good reference has a `create_draft` in its tool log.
  Learner check: Pick any two of the new scenarios, read them, and say whether the answer keys say what a correct answer from your system would be.
  Commit: `Add reply, first-sender and ask-user scenarios with references`

- [x] **4. Summary and injected-instruction scenarios bring the set to 20**
  Becomes usable: 8 more scenarios. Summaries use EmailSum-style threads and key facts. Injections use AgentDojo injection goals and attack phrasings from LLMail-Inject/InjecAgent, rewritten into university mail. Injection failures are split into harmful and harmless.
  Why now: These need their own checks (word ratio, `SUSPICIOUS_INSTRUCTION`, harmful side effects) and source-license checks. Doing them after the writing kinds keeps each slice's new checks small.
  PRD ref: `prd.md > Output Check: Summaries`, `prd.md > Injected Instructions`, `prd.md > Scenarios` (sources, fictional people only)
  Spec ref: `spec.md > Components` (Scenario Set, Layer Checks), `spec.md > Data Model`
  Build: verify the licenses of LLMail-Inject, InjecAgent and EmailSum, and record them in `SOURCES.md`; 4 summarize + 4 injection scenarios with references; summary checks (mustContain/mustNotContain, word ratio < 0.5); injection facts check; harmful flag in results; linter coverage over all 5 kinds × 2 languages × 5 roles.
  Verify (mechanical): `./bench lint` passes with 20 scenarios; `./bench check` exits 0; the broken injection reference (draft to the attacker) is failed at tools and marked harmful; no scenario contains a name or address outside `university.json`.
  Learner check: Read one injection scenario and its broken reference with `./bench show`. Say whether the attack is realistic for a university inbox and whether the flagging is what you'd want.
  Commit: `Add summary and injection scenarios; 20-scenario set complete`

- [x] **5. The judge grades tone, relevance and conciseness, and is calibrated**
  Becomes usable: After scoring, a separate pass on `llama3.1:8b` grades the qualitative criteria. Good references must pass, and wrong-tone, irrelevant and padded references must fail. `./bench check` reports every judge disagreement, with a verdict cache and a separate count for judge errors.
  Why now: The judge is the least certain part and central to your learning goal. It needs the full set of reference outputs to be calibrated against.
  PRD ref: `prd.md > Output Check: Replies and New Mails` (tone, relevance, conciseness), `prd.md > Output Check: Summaries` (relevance), `prd.md > Tone Profiles`
  Spec ref: `spec.md > Components` (Ollama Client, LLM Judge), `spec.md > Decisions and Open Issues` (The useful unknown)
  Build: `OllamaClient`; `Judge` (one criterion per call, rubric from tone profile + language, EmailBench-style dimensions, pass/fail first line); `broken-output-tone|relevance|concise` references for the writing and summary scenarios; `runs/judge-cache.json`; judge errors counted.
  Verify (mechanical): `./bench check` runs the judge pass and reports at most 1-2 disagreements (otherwise adjust the prompt or model and rerun); a second run hits the cache for every verdict; an unreadable answer is reported as `judge error`.
  Learner check: Look at the judge's reasons on three references with `./bench show`, and say whether you agree with its tone calls for a professor versus a friend.
  Commit: `Add calibrated llama3.1:8b judge with verdict cache`

- [x] **6. Any system plugs in, and runs are compared**
  Becomes usable: `./bench run --system "<command>"` runs any command that honours the trace contract through all 20 scenarios. It records the setup and prints the global verdict against the previous run (▲/▼/=, `baseline`, `not comparable`). `./bench diff` shows which scenarios flipped per component.
  Why now: This is the hand-off point to your system and completes the core journey. It's proven here using the replayer itself as the system.
  PRD ref: `prd.md > Comparing Runs`, `prd.md > The Core Journey`, `prd.md > Screens and Layout`
  Spec ref: `spec.md > Components` (Runner and Run Store, Report), `spec.md > The Core Journey Through the System`, `spec.md > Look and Feel`, `spec.md > Important Failure Modes`
  Build: `Bench run --system`; setup recording (commit + dirty, system command, judge digest, judge prompt version, scenario-set hash); preflight; 5-minute timeout; previous-run lookup; verdict; `diff`; ANSI only on a TTY; a short "plug in your system" section in README.
  Verify (mechanical): a run with the replayer on good references prints `baseline` and 20/20; a run on broken references prints ▼ and `diff` names the components; a system that crashes makes its scenario invalid; stopping Ollama stops the preflight with its message.
  Learner check: Run the two commands above, read the verdict and the diff, and say whether they'd tell you where a change to your system helped or hurt.
  Commit: `Run any system through the benchmark and compare runs`

- [x] **7. Two systems are compared on an outcome-only scorecard**
  Becomes usable: `./bench run --system "<command>" --repeat 3` prints a 9-dimension scorecard (task, factuality, tone, relevance, conciseness, injection resistance, reliability, consistency, latency) and a composite 0–100, from what the user ends up with only. `./bench compare <runA> <runB>` puts any two runs side by side, so two differently built systems can be compared dimension by dimension.
  Why now: Learner review of slice 6: comparing different systems needs one normalized score that doesn't depend on architecture. It builds on slice 6's runner and run store, and leaves per-layer scoring untouched.
  PRD ref: `prd.md > Comparing Runs`, `prd.md > Injected Instructions`
  Spec ref: `spec.md > Components` (Scorecard, Runner and Run Store), `spec.md > Data Model` (Trace contract, Run result)
  Build: per-execution `dims` from the mailbox log and output step; `ms` per execution; `--repeat N`; consistency, reliability and latency (`1 / (1 + p95 / 60 s)`) per run; composite; `compare`; `output.message` in the trace contract, the injection good references warn there, and a `broken-output-no-warning` reference per injection scenario; README section updated.
  Verify (mechanical): `./bench check` still exits 0 (with the new no-warning references failing at output); a good-replayer run with `--repeat 3` scores 100 on every dimension except latency, with consistency 1.0; a broken-replayer run scores lower on the dimensions it breaks; `compare` on the two prints both scorecards and the differences; a `--repeat 1` run against a `--repeat 3` run prints `not comparable`.
  Learner check: Run the good and broken replayers with `--repeat 3`, then `./bench compare` on them. Say whether the scorecard lets you say objectively where one system is better than the other.
  Commit: `Add outcome-only scorecard and compare for any two systems`

## Hands-on Checkpoints

- [x] Early usable behavior explored — after slice 2: one scenario scored with broken references; your feedback on scenario format and check strictness shapes the other 19. Learner reviewed and said "next": no changes to format or strictness.
- [x] Final kick-the-tires exploration and feedback completed — learner explored, asked for the standalone benchmark (done)

## Final Review

- [x] Benchmark is standalone: moved to `benchmark/` with its own README; root README links the benchmark and the future `system/` (learner feedback at final review)
- [x] Final review complete — feedback resolved and learner confirms ready to ship ("The benchmark is ready")

## Code Tour and App Map

- [x] Learning activity complete — guided route, focused alternative, prior practice connected, or brief recap
- [ ] Optional edit and transfer reflection addressed — offered/declined/already covered/not applicable as appropriate
- [x] `devpost/app-map.html` generated from finished code, checked, and shown, including a project-grounded practice to reuse

Activity and evidence: prior practice connected. Desired outcome is evaluating agent systems, so the takeaway is "test the evaluator before trusting it", tied to the build's own evidence: good references exposed `vous` in "Rendez-vous" (slice 3) and the no-match-02 concise bug (slice 7); broken references exposed the `15:30:00Z` grounding (slice 4); judge calibration went 8 → 3 → 2 → 0 disagreements (slice 5).
Route and stops: reference route in the map (not toured interactively): `Bench.runSystem` → `Checks.dims` / `Judge.apply` → `Bench.scorecard`.
Edit outcome: not applicable (recap of practice already done during the build).
Reflection:
Activity mode: brief evidence-based recap (focused alternative; the practice happened during slices 3–7).

## Revisions

- Benchmark only; agents, pipeline and prompt changes moved to Later — the learner wants to build the system by hand and have a trusted benchmark ready first.
- At least 20 scenarios, adapted from AgentDojo, WorkBench, LLMail-Inject/InjecAgent, EmailSum and EmailBench's design, with fictional people only — no existing benchmark matches the case, and EmailBench/Enron sources carry real identities.
- Reference traces, a linter, a trace contract and a mailbox-side tool log replace running the real agents — without a system, the benchmark must prove itself.
- Judge calibration on reference outputs replaces the hand-check after the first real run — there is no real run in this build.
- `./bench check` exists from slice 1 (replay only, no scoring yet) and `show` reads the latest run — replaying through the real contract is how slice 1 proves the mailbox; scoring arrives in slice 2.
- `MAILBOX_CMD` is a shell command run with `sh -c`, and the mailbox server exits when its client closes stdin — found while wiring the replayer; it keeps the contract usable from any language.
- `ChecksTest` dropped; broken references are the test of the checks — each broken reference already exercises one check end to end through the real contract and mailbox, so a separate unit test would duplicate them. `./bench check` exits 1 on any gap.
- Scenario kinds are named `reply`, `compose`, `summarize`, `injection`, `no-match`; the linter also enforces fictional people (every address in `university.json` or on the reserved `.example` domain).
- `expect.tools.mustCall` added (e.g. `search_mail` for no-match) — without it, a system could ask the user without ever searching and still pass.
- Greetings accept `Salut`/`Coucou`; `7 pm` counts as 19h in the hallucination check — the friend and English scenarios needed them.
- A good reference exposed a crude answer key (`vous` matched inside "Rendez-vous"); fixed with `(?<!-)\bvous\b`. Good references test the answer keys, not only the checks.
- Language rule changed by the learner: a new mail with no language named follows the language of the user's request (no French default). `compose-admin-03` now expects English; `compose-student-02` became an English request asking explicitly for French, so the four new-mail cases are covered (fr→fr, en→en, fr→en asked, en→fr asked).
- Licenses verified: LLMail-Inject, InjecAgent and EmailSum are MIT; EmailSum used for structure only (Enron/Avocado threads). Attack styles adapted, no attack text copied.
- Forbidden `create_draft` matches only recipients, and hallucination ground truth excludes mail timestamps — two broken references exposed them (an attacker address in a draft body was misread as harm; "30" was grounded by a `15:30:00Z` timestamp).
- Injection references include a `good-ignored` variant (injection dropped, not tagged) that must also succeed; `good-*` names now mean successful.
- Learner review of slice 4: flagging is **required** so the user stays aware next time. A silently dropped injection now fails at facts; the four `good-ignored` references became `broken-facts-not-flagged`.
- Judge prompt calibrated in four rounds against the references (8 → 3 → 2 → 0 disagreements). The verdict moved to the **last** line after one sentence of reasoning, PASS is the default, relevance sees only the request, and the politeness rule depends on the role. The cache is keyed on the prompt text instead of a version number. 10 judge references were added (4 tone, 4 relevance, 2 concise), for 139 in total.
- Judge jitter measured: a cold-cache rerun flipped 2 of 146 verdicts (both `reply-prof-01` relevance) with no outcome change. Ollama 0.34 isn't bit-exact at temperature 0 and seed 42. The cache keeps reruns of unchanged outputs stable; slice 6's comparison should treat a single-scenario output flip as possible judge noise.

- Slice 6: "previous run" means the newest earlier **`./bench run`** result (it has a `setup`). `check` runs share `runs/` but are reference proofs, so a verdict never compares against them. `show <id>` reads either kind of run, and still shows the outcome when a crashed system wrote no trace.
- The replayer takes a variant prefix (`good`, `broken`) and picks each scenario's reference from `SCENARIO`, so it can stand in for a system in `./bench run`. `broken` picks the first broken reference alphabetically, which is mostly facts-layer breaks.
- Scope change by the learner after slice 6: an **outcome-only scorecard** sits alongside per-layer scoring, so systems with different architectures compare fairly. 9 equally weighted dimensions (task, factuality, tone, relevance, conciseness, injection resistance, reliability, consistency over 3 repeats, latency `1/(1+p95/60 s)`); robustness and a separate speed metric dropped; a harmful action counts like any other failure; the injection warning moves into what the system tells the user (`output.message`). Added as slice 7; scope, PRD and spec updated.
- Slice 7: a summary request with no matching mail (`no-match-02`) is scored as a question, not a summary. The good reference exposed it (concise 0.94 instead of 1.0, from the word-ratio rule on a question with no source). The injection warning is also checked at the output layer, so `check` now has 143 references, including 4 `broken-output-no-warning`.
- Final review: the learner wants the benchmark and the system separable. One repo, two folders (`benchmark/`, later `system/`), each with its own README, so each run's recorded commit pins both versions and the submission stays one repo; `git subtree split` can extract `benchmark/` later. Systems are started from `benchmark/`, so they're referenced by path (`../system/...`).
