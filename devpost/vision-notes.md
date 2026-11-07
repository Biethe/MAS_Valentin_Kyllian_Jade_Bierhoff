
# Vision notes: thinking period before building the agent system (2026-11-06)

Not implemented. Read before designing the planner, drafter, summarizer, critic or memory.

### 1. Scope rule (hard requirement)
The system only helps the user with their mailbox: reply, compose, summarize, find mail or attachments.
For anything else it **declines and says so**, without trying the task.
→ Benchmark: a new `OUT_OF_SCOPE` route plus scenarios ("write my essay", "what's the weather",
and an out-of-scope order hidden inside an in-scope request). The planner must route them there, with no tool calls and no draft.

### 2. Why I wouldn't trust it yet, turned into requirements
| Fear | Requirement | How to check it |
|---|---|---|
| Doesn't sound like me (robotic) | **Voice**: draft with the user's tone, how concise they are, the verbs and phrasing they use, and how they reply to each recipient. Most reliable approach: retrieve the user's *own past sent replies* to similar recipients or situations as few-shot examples, plus a short learned style profile. | Hold out real sent mails. A judge compares draft vs. real reply on style only, separate from correctness. |
| Obeys text found in mails or attachments | **Only the user gives orders.** Mail bodies and **attachments** are data. Already in the benchmark for bodies (`SUSPICIOUS_INSTRUCTION`); extend it to attachments. | Injection scenarios with the payload inside an attachment. |
| Misses info, wrong mail, wrong file, wrong recipient, forgotten CC | **Explicit requirement list**: the planner turns the request into a checklist (recipients, CCs, facts to include, attachments to include). A **deterministic** check (no LLM) confirms that every item is in the draft before the user sees it. | Per-item checks in the answer key. Missing CC = harmful. |
| Picked the wrong mail or file | **Show sources**: every draft or summary names the mails and files it used, so the user can check them at a glance. | The trace already records `sourceMailId`. Score that it's the right one. |
| Acts on its own | **Never sends.** It only creates drafts, and the user approves. The user chooses attachments, or confirms any the system proposes. | Already true (`create_draft` only). Keep it that way. |
| Not a teammate | **Assistant mode**: suggests, flags what looks missing ("you didn't answer their 2nd question"), and asks when a request is ambiguous (the ASK_USER route already exists). | Scenarios where the right output includes a suggestion or flag. |

### 3. How the user talks to it (open, deferred)
Both Gmail (Workspace Add-on sidebar) and Outlook (add-in task pane) give you the same shape:
a **side panel next to the open mail**, with a chat box. That's the likely end state.
**Decision for now:** keep the agent core UI-agnostic (instruction + mailbox via MCP in → draft/summary/question + trace out),
and keep driving it from the terminal as the scope says. Pick the UI after the core works.

### 4. Consistency mechanism: critic → long-term memory
Loop: an agent (planner, drafter, summarizer) produces output → the critic and/or the user gives feedback
→ the feedback becomes a **short rule** stored per role → future runs of that role read its rules.

Two memories, as in reflection for agent systems:
- **Short-term (within one task):** while a task is running, the critic's feedback on a mistake goes back to the agent, which retries and fixes *this* result. It's thrown away when the task ends, after its lesson has been extracted.
- **Long-term ("experience"):** the lesson from that mistake is distilled into a rule and kept, so in later tasks the agent doesn't repeat it.
  The benchmark's `learning` score measures exactly this, across repeats. A short-term fix only improves the trial it happens in.

Keeping memory from polluting context:
- **Rules, not transcripts.** Store one-line lessons ("when user lists CCs, copy all of them verbatim"), never whole critiques.
- **Fixed budget for each role** (e.g. ≤ 10–20 rules per agent). When full, merge duplicates and drop the weakest rule.
- **Score each rule**: helpful/harmful counters, updated when a run with that rule succeeds or fails. Low scorers get dropped.
- **Edit the memory, don't rewrite it**: add, edit or delete one rule at a time. Rewriting it all at once tends to shrink it and lose detail.
- **Retrieve by task type** (reply / compose / summarize, recipient role) instead of loading every rule.
- **The user can veto.** The critic can be wrong, and a wrong lesson becomes a lasting regression. Show new rules and let the user reject them.

Research to read (cited from memory, so verify titles and numbers before relying on them):
- *How many instructions at once*: **IFScale**, "How Many Instructions Can LLMs Follow at Once?" (Jaroslawicz et al., 2025): accuracy falls as instructions go up to 500, even the best models land well below perfect, and models start leaving instructions out. **"Curse of Instructions" / ManyIFEval** (Harada et al., 2024): the chance of following *all* N instructions drops roughly like (per-instruction rate)^N. **"Lost in the Middle"** (Liu et al., 2023): the middle of a long context is used least.
  → This is the argument for a small rule budget and for the deterministic checklist in §2.
- *Reflection / memory*: **Reflexion** (Shinn et al., 2023), **ExpeL** (Zhao et al., 2023: insights edited with ADD/EDIT/UPVOTE/DOWNVOTE, close to your idea), **Dynamic Cheatsheet** (Suzgun et al., 2025), **ACE: Agentic Context Engineering** (2025: small delta updates instead of full rewrites, so context doesn't collapse).
- *Measuring drops*: continual-learning **backward transfer / forgetting** (Lopez-Paz & Ranzato, GEM, 2017), the closest existing metric to your drop count.

### 5. Consistency and learning metrics
Setup: run the system T times (e.g. T = 5) with memory carried over between runs. Run 1 is the baseline b = s₁.

**Score A: Drop count** = how many t in 2..T have s_t < s_{t−1} − ε (normalized: divide by T−1; 0 is ideal).
Every drop counts, above or below the baseline. Staying flat (a plateau, "hitting a wall") is not a drop.

**Score B: Area** = the area under the curve, using trapezoids on 0–1 scores, divided by T−1. It goes from 0 to 1.
Revised on 2026-11-06. The first version measured area against the baseline (trial 1), as (A⁺ − A⁻)/(A⁺ + A⁻), but that can't tell
a system that *starts* failing from one that starts passing, nor a big gain from a small one. Measuring against 0 does both:
a bad start, a small gain or a low plateau all lower it, and a flat curve scores its own level.

**Score C: Learning** = D × A, from 0 to 1, where D = 1 − drops/(T−1). It punishes inconsistency (drops) and not learning
(a low area). Reported next to it: `gain` = last − first.
It is computed at **every level**: per scenario, per kind (reply, summarize, injection…), per dimension (task, tone…) and overall.
The scorecard's `learning` dimension is the mean over scenarios, so one scenario improving can't hide another dropping.

| trials | D | A | C |
|---|---|---|---|
| ✔ ✔ ✔ | 1 | 1 | **1** (perfect from the start) |
| ✘ ✔ ✔ | 1 | 0.75 | **0.75** (learned, but started failing) |
| ✘ ✘ ✔ | 1 | 0.25 | **0.25** (learned late) |
| ✔ ✘ ✔ | 0.5 | 0.5 | **0.25** (inconsistent) |
| ✘ ✘ ✘ | 1 | 0 | **0** (never learned) |
| 0.88, 0.89, 0.87, 0.90, 0.88 | 0.5 | 0.885 | **0.44** |

Pitfalls to design around before trusting the numbers:
1. **Same task each run = memorization, not learning.** Let memory learn from a *train* set of scenarios and measure on a *held-out* set. Otherwise the curve only shows it memorized the answer keys.
2. **Noise.** The local model isn't deterministic, so 89 → 87 might be noise. Repeat each run k times (the spec already has `repeat`), and count a drop only when it's bigger than ε, i.e. past the run-to-run spread.
3. **Ceiling.** Handled: a plateau at 100% scores 1.
4. **Order matters.** Report the scores per scenario kind too (reply / summarize / injection). A gain on one kind can hide a drop on another.

