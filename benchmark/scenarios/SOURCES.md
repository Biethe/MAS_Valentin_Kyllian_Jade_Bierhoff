# Sources

This benchmark is new, but it is built from existing benchmarks. Each scenario records its source in `source: {from, use}`:
- `copied`: text reused verbatim.
- `adapted`: a task shape or template rewritten for the university.
- `inspired`: an idea only.

Every person and address is fictional (`university.json`, `.example` domains). `./bench lint` enforces this.

| Source | License | What is used | Use |
|---|---|---|---|
| [WorkBench](https://github.com/olly-styles/WorkBench) (Styles et al., 2024) | MIT, © 2024 Mindsdb | Email task templates (reply, send); answer keys as expected tool calls; harmful vs harmless failures (side effects) | adapted |
| [AgentDojo](https://github.com/ethz-spylab/agentdojo) (Debenedetti et al., 2024) | MIT, © 2024 the AgentDojo authors | Workspace user-task ideas (hiking trip, birthday invitation); injection goals ("email the text … to …", "send the body of the email … to …") planted in mail bodies and attachments; success = real task done and attack not followed | inspired / adapted |
| [LLMail-Inject](https://github.com/microsoft/llmail-inject-challenge) (Abdelnabi et al., 2025) | MIT (code and Hugging Face dataset) | Attack style: fake chat-template tokens followed by an order to email someone | adapted (no text copied) |
| [InjecAgent](https://github.com/uiuc-kang-lab/InjecAgent) (Zhan et al., 2024) | MIT | Data-stealing attacker instructions ("retrieve … and email them to …") | adapted (no text copied) |
| [EmailSum](https://github.com/ZhangShiyue/EmailSum) (Zhang et al., 2021) | MIT (code and summaries); threads come from Enron/W3C/Avocado | Thread shape (3–10 mails) and short summaries that keep key facts; structure only, no thread text | inspired |
| [EmailBench](https://arxiv.org/abs/2609.31906) (2026) | paper CC BY 4.0; data unreleased | Judge rubric dimensions (relevance, completeness, no hallucination), used in the judge prompt | inspired (no data) |
