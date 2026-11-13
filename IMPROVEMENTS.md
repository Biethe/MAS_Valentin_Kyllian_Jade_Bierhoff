# Benchmark improvements (2026-11-12)

Same architecture: planner → brief → writer or summarizer. Benchmark code untouched.

| Run | Composite | p95 latency | Passing | Note |
|---|---|---|---|---|
| Before Cursor | ~27 | 480 s | 0/20 | |
| After Cursor (uncommitted) | 67.7 | 181 s | 8/20 | 2 timeouts |
| One model for every role | 62.2 | 42 s | 4/20 | latency fixed, split duplicates drafts |
| Planner, writer, summary fixes | 78.6 | 15 s | 9/20 | |
| + no split for one task, cc from brief, stem match | 88.5 | 8.5 s | 15/20 | |
| + accent-safe regex, register hint | 93.5 | 9.5 s | 17/20 | |
| + no inbox search for a new mail | 98.3 | 9.3 s | 20/20 | |
| + skip routing call when the verb decides | **98.6** (`--repeat 3`) | 8.7 s | 20/20 ×3 | learning 1.0, no drops |

The latency dimension is `1 / (1 + p95 / 60 s)`. At 8.7 s it scores 0.87, which is the only dimension below 1.

## What changed

### Latency
- **One model for every agent role, `llama3.1:8b`** (`OllamaAgent` defaults). Before, four models (qwen3.5 2b and 4b, llama3.2, tev1) plus the 8B judge were swapped in and out of 8 GB of VRAM on every scenario. A turn took 12–30 s alone but 150 s+ inside the benchmark. Now one model stays loaded, and the judge shares it.
- **Removed `num_ctx: 8192`.** A different context size makes Ollama load a second copy of the same model.
- **Fewer model calls:**
  - The split call runs only when a summary and a draft are both asked for.
  - The routing call runs only when no task verb is found.
  - The summary's checklist marking call is skipped when the checklist is empty.
  - A new mail no longer searches the inbox.

### Planner (`Planner.java`)
- **Over-splitting fixed.** One task verb means one instruction. `llama3.1:8b` was splitting one request into up to six instructions, and each one saved a draft (marked harmful). Two instructions of the same type are also merged.
- **Accent-safe verb regexes (`(?U)`).** Java's `\b` does not match before `é`, so "Écris…" was never recognised as a write request.
- **Pronoun question narrowed.** "Who is the recipient?" is asked only when no name is given ("tell him" next to "Tom" used to be blocked).
- **Reply recipients:** a reply goes to the sender of the mail with no cc. The model sometimes addressed it to the owner.
- **New-mail recipients:** a new mail uses the contacts-file match first, then the model's guess. The contact's role (friend, professor…) is copied into the brief.
- **The owner's own request becomes a fact for replies too** ("Owner's request: …"). Before, the writer never learned what to say.
- **Attachments of the picked mail are read**, so an order hidden in an attachment is flagged.
- **Injection handling:**
  - A line that addresses an assistant (`assistant`, `IA`, `chatbot`, `<|…|>` tokens, "ignore previous", "ne mentionne pas") is a SUSPICIOUS_INSTRUCTION fact.
  - It is removed from the mail and attachment text the writer sees.
  - The user is warned in `output.message`.
- **Topic check:** compares 5-letter stems, so a French request matches an English mail (séminaire / seminar).
- **New self-check:** `java … com.assistant.mail.workflow.planner.Planner`.

### Writer (`WriterWorkflow.java`, `DrafterSystemPrompt.TEXT`)
- **Voice:** writes as the mailbox owner, never signs with the sender's name, and greets the recipient by name.
- **Content:** says exactly what the owner asked, with its dates, and recalls the key date of the mail being answered.
- **Register:** a friend contact, or a mail that opens with Salut, Hi or Hey, gets an explicit informal hint in the draft's language (tu / Salut / À plus, or Hi / Cheers).
- **No suspicious facts:** the writer never sees them.
- **cc comes only from the brief**, never from the model's text, so an injected address can't become a cc.
- **Removed Cursor's hack** that appended a raw mail line with a digit to the body.

### Summarizer (`SummaryWorkflow.java`, `SummarizerSystemPrompt.TEXT`)
- **Removed Cursor's `grounded()` hack.** It replaced the model's summary with the first raw lines of the mail, so other mails, greetings and sign-offs ended up in it.
- **New prompt:**
  - Covers every matching mail.
  - Keeps every date, place, deadline and action in the mail's own words.
  - Leaves out optional leisure events.
  - Gets a word budget, `maxWords` = 40% of the source words.
- **Facts are not repeated** when the mails are already in the prompt.

### Trace (`TraceFile.java`)
- **Warning line:** the message starts with "Warning: this mail gives orders to the assistant. I did not follow them: …".
- **Empty progress removed:** the "0/0" checklist line is dropped.

## Still off
The critic debate (critic → argument → tev1 judge → lesson) is still disabled with `if (true) return` / `if (false)` in all three workflows, and the planner's checklist is still empty. Cursor did this to save time. Turning them back on costs about 3 model calls per draft. The quality dimensions are already at 1.0, so measure the latency cost first.

## What Cursor changed (commit 4817419 and the uncommitted diff before this pass)
- **Model defaults** moved to qwen3.5:2b (writer), qwen3.5:4b (planner), llama3.2 (critic) and tev1:0.8b (judge).
- **Critic and debate disabled:**
  - Writer and summarizer: `if (true) return` in `debate`.
  - Planner: `if (false)` on its critic.
  - Checklist replaced by an empty `Checklist`.
- **Turned off:**
  - The model's mail selection (LOCATE).
  - Meaning-based search before keyword search.
  - Model fact extraction (READ), replaced by "every line of the mail is a fact" plus a keyword check for injections.
- **Planner additions:**
  - `directory()`: recipient lookup in `benchmark/scenarios/university.json`.
  - A pronoun check.
  - Sender and topic filters (`topic`, `covers`, `senders`).
  - A "which one?" question when several senders match.
- **Writer:**
  - Recipients forced from the brief.
  - Default subject "Message".
  - Automatic Bonjour or Hello greeting.
  - The digit-line hack (now removed).
- **Summarizer:** the `grounded()` override (now removed).
- **Benchmark changes:**
  - `BENCH_MINUTES` env var for the timeout. Some of Cursor's runs used 3 minutes, so they don't compare with the 5-minute default.
  - `MailboxServer` search: ignores `in:inbox`-style operators, an operator-only query lists the inbox, and adds a `before:` date search.
- **Other:**
  - `PlannerSystemPrompt` ROUTE, BRIEF and TOOLS wording.
  - Outlook draft web links (`draftLink`).
  - Files moved into `workflow/planner|shared|summary|turn|writer` packages.
  - Browser extension UI.

## Caveats
- **The judge grades its own model.** The system and the judge are both `llama3.1:8b`, and a judge can favour its own model's writing. To check, re-run with `AGENT_MODEL=qwen3.5:4b PLANNER_MODEL=qwen3.5:4b` and compare with `./bench compare`.
- **The contacts file is benchmark data.** Recipient and role lookup reads `university.json`, a benchmark scenario file. On Gmail or Outlook it is missing and the planner falls back to the model's guess. A real address book would replace it.
- **Some rules are keyword-based and English/French only:** task verbs, the informal check and the injection markers.

---

# Round 2 (2026-11-13): harder benchmark, real search

## Benchmark
- **Fixed a search bug from Cursor's commit 4817419.** `MailboxServer.searchMail` started from `all = words.isEmpty()`, so every keyword query returned nothing. Only `in:inbox` listed mail. All dev scores before this fix (including 98.6) were reached without working search. `MailboxServer check` now guards it.
- **Search pages:** at most 10 hits, newest first.
- **Scenario sets via `BENCH_SET`.** Two new sets:
  - `scenarios-hard/` (10, for development).
  - `scenarios-heldout/` (16, final check only).
  - New kinds: thread, multi-hop, multi-task, out-of-scope.
  - Subtle injections without an "assistant" marker.
- **Scoring:**
  - One request can be scored on both a summary and a draft. Required words may sit in either.
  - `output.refusal` is scored for out-of-scope requests.
- **Judge:** `BENCH_JUDGE` picks another judge model.
- **Run comparisons** stay within the same set.
- **Reference traces:** 26 new correct ones and 5 deliberately broken ones, all scored as expected. The 144 old references still pass.

## System
- **Planner flow** for each instruction:
  1. Route (the model decides; a verb never overrides OUTOFSCOPE or CHAT).
  2. Short keyword queries from the model, plus one search per name or content word.
  3. Read the best 14 hits in full.
  4. **Locate:** the model picks `reply`, `use` and an optional follow-up `search`, which runs one more hop.
  5. Read the thread of every pick. A reply answers the newest mail of that thread not sent by the owner.
- **Guards (deterministic):**
  - A supporting mail must share a content word with the request; dates and weekdays don't count.
  - A new mail takes facts from other mail only when the request points to one ("m'a envoyé", "sent me").
  - A reply "about X" needs a mail that mentions X.
  - Another thread matching the request as well as the chosen one makes the planner ask "which one?".
  - "Mails from X" means every mail from X.
  - Summaries widen to the same sender's related mails.
- **Injection check:** a model call flags lines that order an automated tool, on top of the keyword check.
- **Robust parsing:** model answers without labels still parse (`labeled()`), and a bare route type is accepted.
- **Summaries:** one shortening pass when over the word budget (40% of the source words). Threads are given in date order, and the latest version wins.
- **Writer:** never invents numbers, links or references, and never writes placeholders like "Cher(e)".
- **Debugging:** `MAIL_DEBUG=1` prints every model answer and the mails that were picked.
- **Trace:** a refusal is written to `output.refusal`.

## Results

| Set | Before | After |
|---|---|---|
| `scenarios/` (20) | 20/20, 98.6 (search broken, so never tested) | 20/20, 98.3 (search working) |
| `scenarios-hard/` (10) | 0/10, 55.7 | 10/10, 97.5; final run 9/10, 95.9 (judge failed a correct summary) |
| `scenarios-heldout/` (16, not tuned on) | 1/16, 63.6 | **10/16, 90.5** |

p95 latency went from 9 s to about 15 s: 3–4 more model calls on hard requests.

## Held-out failures (not fixed, to keep the set honest)
- **multi-hop-01:** the model picked the booking mail as the one to answer; the "which one?" guard then asked about Maya's mail.
- **multi-hop-02:** two people are named; recipient lookup picks by best name match (a tie), not the person after "Écris au".
- **multi-hop-04:** two mails from the same office in different threads made the guard ask instead of using one as a fact source.
- **no-match-05:** "about Saturday": weekdays no longer count as topic words, so the ambiguity was missed.
- **thread-01:** the summary dropped the external examiner joining by video.
- **thread-04:** the answer key forbids any mention of "parking du lac", but saying it is closed is correct. The key is too strict.

Fixing these now would be tuning on held-out. Fix them, then write a new held-out set.

---

# Round 3 (2026-11-13): memory back on, measured without latency

## Why memory was off, and why it would not have worked when turned on
- Cursor turned the critic debate off with `if (true) return` in `WriterWorkflow` and `SummaryWorkflow`. The debate is the only thing that writes long-term lessons. It is now behind `MAIL_CRITIC=1`.
- Three bugs kept memory empty even with the debate on:
  1. **Runaway critic.** At temperature 0 the critic listed every part forever and hit the 5-minute timeout. Every call is now capped with `num_predict: 1024`.
  2. **Parser.** The critic writes `part: TONE` on one line, but the parser only read `part:` alone on a line, so every critique was dropped. `WriterWorkflow.blocks` reads both forms, and the summarizer now reuses it.
  3. **Unlabeled answers.** The lesson model answers in plain text with no `lesson:` label, so every lesson was empty and nothing was saved. `answer()` falls back to the whole text.
- **Noise and cost.** Critic blocks that only agree ("The tone is correct.") are skipped, and a draft gets at most 2 disputes.
- **Retrieval froze.** Rules all score 0 (helpful and harmful are never updated), so the first 10 lessons were returned forever. The newest lessons now win ties.

## Benchmark
- `BENCH_LATENCY=0` leaves latency out of the composite. p95 is still printed, and the setting is recorded so mixed runs are marked not comparable.

## Experiment: `scenarios/` (20), `--repeat 3`, latency off, same critic in both arms
| | No memory (lessons thrown away) | Memory (one shared `MEMORY_FILE`) |
|---|---|---|
| Composite | 97.9 | 98.0 |
| Trials (mean score) | 0.992 → 0.975 → 0.975 (gain −0.017) | 0.982 → 0.983 → 0.992 (gain +0.010) |
| Overall learning (curve of the means) | 0.490 (1 drop) | 0.985 (no drop) |
| Learning dimension | 0.960 | 0.939 |
| factuality / relevance | 0.917 / 0.958 | 0.967 / 0.938 |
| p95 latency | 17.1 s | 17.1 s |
| Lessons stored | 0 | 69 (`benchmark/runs/20261113-122803/memory.json`) |

**Reading.** Within noise, there is no difference: one scenario out of 20 moves a dimension by 5%. The lessons explain why:
- Many are generic ("include a phrase that expresses gratitude").
- Many are repeated across trials.
- Many are wrong: "address the reply to the owner", "the TO field should include the sender's address", "avoid imperative verbs like *send*".

They did no harm only because recipients, cc and injection handling are deterministic and ignore the model's text. The critic only sees the brief, not the outcome, so the memory learns the critic's opinions, not what actually went wrong.

**Next steps if memory is to help:**
- Ground lessons in an outcome: the user's veto or edit, or a check that failed.
- Update helpful/harmful when a rule's next use passes or fails.
- Deduplicate lessons.
- Test transfer by freezing the memory file and running `scenarios-hard/` once with it and once without.
