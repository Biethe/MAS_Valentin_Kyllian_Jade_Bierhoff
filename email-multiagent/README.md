# Email Multi-Agent System

A Java 17 multi-agent assistant that reads a mailbox over IMAP, routes each
email to a specialized agent, and drafts a reply with a **local LLM**. The user
reviews the draft, edits it, then decides to send or reject it. A web UI drives
the whole flow; there is no desktop GUI.

> M2 Artificial Intelligence — Multi-Agent Systems project.

---

## 1. What it does

- **Reads Gmail (or any IMAP mailbox)** over SSL, 50 messages by default.
- **Sorts mail into sub-inboxes**: Student, Teacher, Administration, Research,
  Generic, Spam, No-reply, Quarantine.
- **Runs a security check first** (prompt injection, sensitive-data requests);
  suspicious mail is quarantined before any agent sees it.
- **Summarizes each selected email** and decides whether it needs a reply.
- **Pre-generates an editable draft** when a reply is needed.
- **Sends only after explicit confirmation**, then feeds the sent reply back into
  the contact knowledge base.
- **Auto-refreshes** the inbox every 60 s.
- Ships an **audit log**, a **feedback store**, and a **metrics dashboard**.

## 2. Architecture

```
                    ┌──────────────────────────┐
  IMAP inbox ──────▶│ SecurityAgent (A0)       │
                    │ injection / sensitive    │
                    └────────────┬─────────────┘
                                 │ safe
                                 ▼
                    ┌──────────────────────────┐
                    │ RouterAgent              │
                    │ RouterStrategy            │
                    │ keyword │ LLM │ hybrid    │
                    └────────────┬─────────────┘
                                 │ category + confidence
      ┌──────────────┬───────────┼───────────┬──────────────┐
      ▼              ▼           ▼           ▼              ▼
 StudentAgent  TeacherAgent  AdminAgent  ResearchAgent  GenericAgent
      └──────────────┴───────────┼───────────┴──────────────┘
                                 ▼
                     PromptRepository + ContextProvider (RAG)
                                 ▼
                          LLMClient (Ollama / mock)
                                 ▼
                SummaryAgent ──▶ summary + draft ──▶ Web UI
                                 ▼
                     Sent reply ──▶ RecipientKnowledgeBase
```

- **RouterAgent** only classifies and delegates; it never writes the reply.
- **Specialized agents** own a prompt, a context category, and thread memory.
- **SummaryAgent** is the seventh agent: it summarizes and pre-drafts.
- **LLMClient** is a port, so the model can be swapped without touching agents.
- **RecipientKnowledgeBase** stores what is known about each contact, injects it
  into the specialist and summary prompts, and learns from replies the user
  approves.

### Design patterns

| Pattern | Where |
|---|---|
| Strategy | `RouterStrategy` (keyword / LLM / hybrid / feedback) |
| Factory | `AgentFactory`, `RouterFactory`, `LLMClientFactory`, `MailClientFactory` |
| Registry | `AgentRegistry` |
| Template method | `AbstractAgent.handle` |
| Adapter | `ImapMailClient`, `SmtpMailSender`, `OllamaClient`, `OllamaEmbeddingClient` |
| Composition root | `AppContext` |

## 3. Modular layout

The project is a **Maven multi-module reactor**. Each module has a single
responsibility and only depends on the modules to its left:

```
email-multiagent/
├── pom.xml                  aggregator + shared dependency versions
├── email-core/              domain and orchestration — no transport, no UI
│   └── src/main/java/fr/mas/email/
│       ├── model/           EmailMessage, EmailCategory, RoutingDecision, AgentResponse
│       ├── agent/           Agent, AbstractAgent, RouterAgent, SummaryAgent, registry, factory
│       ├── agent/specialized/  Student, Teacher, Administration, Research, Generic
│       ├── routing/         RouterStrategy + keyword / LLM / hybrid / feedback
│       ├── llm/             LLMClient port + Ollama and mock adapters
│       ├── context/         ContextProvider, File/RAG providers, embeddings, contacts
│       ├── prompt/          PromptRepository (classpath prompts, per language)
│       ├── memory/          ConversationMemory
│       ├── security/        SecurityAgent, SecurityResult
│       ├── audit/           AuditEntry, AuditLog (JSONL)
│       ├── feedback/        FeedbackEntry, FeedbackStore (JSONL)
│       ├── metrics/         MetricsService
│       ├── system/          EmailAssistantSystem (orchestration)
│       ├── config/          AppConfig
│       └── evaluation/      labeled-dataset runner
│   └── src/main/resources/
│       ├── config.properties
│       ├── prompts/*.md     one prompt per agent, `_fr` variants
│       └── evaluation/emails.jsonl
│
├── email-mail/              transport adapters (IMAP + SMTP)
│   └── src/main/java/fr/mas/email/mail/
│       ├── MailClient, ImapMailClient
│       ├── MailSender, SmtpMailSender
│       ├── factories, MailException, MailMain
│
├── email-app/               composition root + HTTP + UI
│   └── src/main/java/fr/mas/email/
│       ├── app/AppContext   wires every concrete implementation
│       ├── web/WebServer    JSON API + static files
│       ├── web/MailItem     API DTO
│       └── Main.java        console demo
│   └── src/test/            all tests (need the repository root as cwd)
│
├── web/                     index.html, metrics.html
├── context/                 editable document context per category
├── archi.html               French architecture/spec page
└── logs/                    audit-*.jsonl, feedback-*.jsonl (created at runtime)
```

### Dependency rules

```
email-core   ←  email-mail  ←  email-app
   (no mail)      (no web)       (wiring + HTTP)
```

- `email-core` must not import `fr.mas.email.mail` or `fr.mas.email.web`.
- `email-mail` must not import `fr.mas.email.web`.
- Only `AppContext` chooses concrete classes. Everything else receives
  interfaces through constructors.

This is what makes the project easy to merge: copy `email-core` alone if the
other project already has a transport/UI, or copy `AppContext` + `web` and swap
the adapters.

## 4. Requirements

- JDK 17+
- Maven 3.9+
- Optional: [Ollama](https://ollama.com) for real generations
- Optional: an IMAP/SMTP account (Gmail + app password works)

## 5. Build, test, run

```bash
# from the repository root
mvn test                 # 12 tests
mvn -DskipTests install  # builds core, mail and app
mvn -pl email-app exec:java
```

or simply:

```bash
./run.sh
```

The server starts on **http://localhost:8080**. Keep the repository root as the
working directory: `web/`, `context/` and `logs/` are resolved from there. The
`exec` plugin is already configured to do this for you.

Other entry points:

```bash
# console demo (same wiring as the web app)
mvn -q -pl email-app exec:java -Dexec.mainClass=fr.mas.email.Main

# IMAP smoke test
mvn -q -pl email-mail exec:java -Dexec.mainClass=fr.mas.email.mail.MailMain

# router evaluation on the labeled dataset
mvn -q -pl email-core exec:java -Dexec.mainClass=fr.mas.email.evaluation.EvaluationMain
```

> The last two need the modules installed first (`mvn -DskipTests install`).

## 6. Web interface

Open http://localhost:8080.

| Area | What it does |
|---|---|
| Sub-inboxes | Filters by category; `Quarantine` shows blocked mail |
| Inbox list | Paginated (10 per page), Previous/Next |
| Search | Matches sender, subject and body |
| Summary | Generated when a mail is selected |
| Pre-generated reply | Editable textarea, regenerate button |
| Show full mail | Body hidden by default |
| Send / Reject | Send asks for confirmation; reject records feedback |
| Recipients | Knowledge about the current sender |
| Known contacts | Click a contact to load its knowledge |
| Language | English / French UI and prompts |
| Metrics link | Opens the dashboard |

The inbox refreshes every 60 seconds. Loading it hits Gmail over IMAP and can
take ~30 s; a Retry button is shown if it fails.

### HTTP API

| Method | Path | Purpose |
|---|---|---|
| GET | `/api/inbox?page=&pageSize=&category=&search=` | Paginated, routed inbox |
| POST | `/api/route` | Route one email and draft a reply |
| POST | `/api/route-all` | Route a batch |
| POST | `/api/summary` | Summarize + decide + pre-draft |
| POST | `/api/send` | Send an approved reply |
| GET | `/api/recipient?email=` | Contact knowledge |
| GET | `/api/recipients` | All known contacts |
| GET/POST | `/api/feedback` | Read / record accept-reject decisions |
| GET | `/api/metrics` | Dashboard counters |
| GET | `/api/audit` | Audit events |
| GET/POST | `/api/language` | Read / switch the server-side prompt language |

## 7. Configuration

All settings live in `email-core/src/main/resources/config.properties`.

```properties
# LLM: mock (offline) or ollama (local server)
llm.client=ollama
llm.model=huihui_ai/llama3.2-abliterate:3b
llm.endpoint=http://localhost:11434
llm.timeout.seconds=120

# Routing: hybrid (keyword first), llm (LLM first), keyword
routing.strategy=hybrid
routing.threshold=0.5
routing.llm.confidence=0.6
routing.hybrid.confidence=0.6

# Retrieval-augmented context
rag.enabled=true
rag.embedding.model=all-minilm:22m
rag.embedding.timeout.seconds=120
rag.topK=3

# UI language at startup: en or fr (the selector can change it at runtime)
ui.language=en

memory.max.entries=10
mail.provider=imap
mail.top=50

# IMAP
imap.host=imap.gmail.com
imap.port=993
imap.ssl=true
imap.folder=INBOX
imap.username=your-email@gmail.com
imap.password=your-app-password
imap.userEmail=your-email@gmail.com

# SMTP (used to send approved replies)
smtp.host=smtp.gmail.com
smtp.port=587
smtp.starttls=true
smtp.username=your-email@gmail.com
smtp.password=your-app-password
```

For Gmail: enable 2-step verification, create an **app password**, and use it for
both IMAP and SMTP. Do not use your account password.

To run fully offline set `llm.client=mock`; the mock client returns a
deterministic draft and needs no model.

## 8. Extending and merging

- **New agent**: extend `AbstractAgent`, give it an `EmailCategory` and a prompt,
  register it in `AgentFactory`. If it handles a new category, add it to
  `EmailCategory` and to the keyword/LLM routers.
- **New prompt**: add `prompts/<name>.md` (and `<name>_fr.md`) in `email-core`.
- **New context**: add files under `context/<category>/`; RAG indexes them
  automatically.
- **New model**: implement `LLMClient`, return it from `LLMClientFactory`.
- **New mail provider**: implement `MailClient` / `MailSender` and return it from
  the corresponding factory. Nothing else changes.
- **New transport/UI**: depend on `email-core` only, build your own composition
  root, and reuse `EmailAssistantSystem`.

### Merging checklist

1. Add `email-core` as a dependency of the other project (or copy the package).
2. Reuse your own transport by implementing `MailClient` / `MailSender`.
3. Reuse your own UI by calling `EmailAssistantSystem.handle(email)` and
   `SummaryAgent.summarize(email)`.
4. Keep one composition root. Do not instantiate agents or clients in the UI.

## 9. Evaluation

`email-core/src/main/resources/evaluation/emails.jsonl` contains labeled
examples. `EvaluationMain` compares the keyword, LLM and hybrid routers and
prints accuracy, per-category results and disagreements.

```bash
mvn -q -pl email-core exec:java -Dexec.mainClass=fr.mas.email.evaluation.EvaluationMain
# custom dataset
mvn -q -pl email-core exec:java -Dexec.mainClass=fr.mas.email.evaluation.EvaluationMain \
  -Dexec.args="--dataset path/to/emails.jsonl"
```

## 10. Privacy and safety

- Everything runs locally; prompts go to a local Ollama instance, not a cloud.
- The assistant **never sends automatically**: every send requires confirmation.
- `SecurityAgent` quarantines prompt-injection attempts and sensitive-data
  requests before routing.
- `logs/audit-*.jsonl` is append-only; `logs/feedback-*.jsonl` records user
  decisions. Both are local.
- Credentials are stored in `config.properties`. For a public repository, move
  them to environment variables or a local, git-ignored file.

## 11. Troubleshooting

| Symptom | Fix |
|---|---|
| Inbox takes ~30 s or fails | Gmail IMAP latency; use Retry, or lower `mail.top` |
| `Connection refused` on generations | Start `ollama serve` and pull the configured model |
| `IMAP credentials are not configured` | Fill `imap.username` / `imap.password` |
| `[RAG] embedding model unavailable` in the log | Run `ollama list` and set `rag.embedding.model` to the exact installed tag (for example `all-minilm:22m`) |
| `class ... not found` when running a module directly | Run `mvn -DskipTests install` first |
| Static page 404 | Start from the repository root (`./run.sh`) |
| Wrong language | Set `ui.language` or use the language selector |

## 12. License

Academic project — use and adapt freely for coursework.
