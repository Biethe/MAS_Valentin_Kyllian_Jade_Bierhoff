---
doc: scope
status: approved
---

# My Mail Agent: Benchmark-First Slice

A traceable, benchmarked local multi-agent mail assistant for a university. The benchmark comes first, so every change answers "is the system getting better?"

## The Unique Kernel
A **fictional university** is the ground truth. Each scenario defines the expected behavior at **every layer**, not just the final email: the route the planner picks, the tool calls, the facts pulled in, and the tone for the recipient's role (professor, researcher, student, administration). Every decision is traced and scored on its own, and the whole run is scored end to end.

**Built from existing benchmarks** (revised in `5-build`): the scenarios, answer keys and judge rubrics are adapted from AgentDojo, WorkBench, LLMail-Inject/InjecAgent, EmailSum and EmailBench's design, then rewritten for a university with only fictional people. No existing benchmark covers per-layer scoring, tone by role, French and the ask-user path, so those parts are new. The benchmark is built and proven first; the learner builds the agent system themselves afterwards and plugs it in.

## Who It's For
- **Now:** the learner, as the developer iterating on the system. They need to see which decision at which layer got better or worse after each change.
- **Eventually:** someone at a university dealing with "tons and tons of emails" (a professor, researcher, student or administrator). They need replies, new mails and summaries in the right tone for each recipient, without their mail leaving their machine. The project came from one of the learner's professors.

## The Core Loop
Change something in the system → run the benchmark → the real agents (planner/router, writer, summarizer) on a local model work through the fictional-university scenarios → every decision's input and output is traced → each decision is scored against the scenario's expectations, plus a whole-system score → see what improved, what regressed, and where → change again.

## Inspiration & Identity
Terminal-only, local, sovereign: "it's not an app… it's gonna remain in the terminal." Close to deterministic, failures explainable. Research map for the benchmark:
- [AgentDojo](https://neurips.cc/virtual/2024/poster/97522) ([paper](https://arxiv.org/abs/2406.13352)): email/calendar/cloud workspace with prompt injections placed inside email bodies; success checked by deterministic functions over the final environment state.
- [WorkBench](https://arxiv.org/abs/2405.00823): simulated email, calendar and other databases; outcome-centric grading against the expected final state, with no LLM judge.
- [Berkeley Function Calling Leaderboard](https://gorilla.cs.berkeley.edu/leaderboard.html): tool-call correctness via AST matching, close to the deterministic tool-call validator idea.

## Why This Matters to the Learner
"A very strong agentic system, with deterministic or evaluatable behavior and outcomes", one that "knows when it fails… tells you why it doesn't work, you can trace everything back." The benchmark lets them, "every time I do something… check, am I going in a good direction… is the system getting better?" The learning goal is how to evaluate an agent system.

## What "Working" Looks Like
In their words: "when I can trace every single decision, you know, input output that happens in my system. I can trace it, I can evaluate it. So I can tell if that is good or bad."

The proof moment: a scenario fails, and the trace points to the exact layer and decision that went wrong (for example a wrong route, a wrong tool call, or a student's tone used with a professor) instead of just a bad final email.

## The POC Boundary
- **Local** deployment, one user's agent.
- **The fictional university:** people with roles, their mail, and scenarios including edge cases.
- **The benchmark only.** It is proven with hand-written reference traces (good and deliberately broken), not with the real agents (revised in `5-build`).
- **Tracing** of every decision's input and output at every layer.
- **Evaluation** per layer against scenario expectations, plus a whole-system evaluation: an outcome-only scorecard that compares any two systems, whatever their architecture (added in `5-build`).
- **Component map** at the design level: agents, the data objects they exchange, and how they interact. No implementation details.

## Later
- **The agent system itself** (planner/router, writer, summarizer on a local model): the learner builds it by hand to understand it deeply, then plugs it into the benchmark through the trace contract.
- **Central deployment:** a university-hosted server for many trusted users, including tenant isolation (user A's mail must never reach user B). "Requires more thinking and more time… a layer of complexity I haven't really thought through."
- **Critic agent** replacing the current validator, looping feedback to the planner, summarizer and writer.
- **Deterministic runtime tool-call validator**, with no LLM: format (JSON/XML), parameter count and schema on every MCP call.
- **Memory component** and its own evaluation. Not thought through yet.
- **Vision notes** (scope rule, trust requirements, critic memory, consistency metrics): see `vision-notes.md`.
- **Live demos** against Google and Microsoft mail MCPs.

## Explicitly Cut
- **Any UI or app.** It stays in the terminal and reaches the world through MCPs.
- **"Functions properly all the time".** That's the long-term goal; this slice makes progress toward it measurable.

## Open Questions for PRD/Spec
- How the fictional university's mailbox reaches the agents in a reproducible run, given that live Gmail/Outlook isn't static. A simulated mail MCP seems implied.
- How to evaluate the whole system: "I'll have to find out a way."
- How to grade open-ended outputs (reply quality, tone) objectively.
- Whether prompt-injection scenarios are in the first scenario set.
- Whose agent each scenario runs as (which role owns the local agent).
- How to keep scores comparable when a local model isn't fully deterministic.
