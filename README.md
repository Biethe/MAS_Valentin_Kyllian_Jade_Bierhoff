# Mail assistant

A local assistant that reads a mailbox, summarizes mail, and saves drafts. It never sends. The chat runs in the browser. The agents run in Java and call a local [Ollama](https://ollama.com) model. `MAIL_MCP` chooses the mailbox: a fictional university inbox, Gmail, or Outlook.

## What you need

- Java 21 and Maven
- Node.js (for the chat page)
- Ollama, with these models

```bash
ollama pull llama3.1:8b
ollama pull tev1:0.8b
ollama pull all-minilm:22m
```

Every agent role uses `llama3.1:8b` by default: the writer and the summarizer (`AGENT_MODEL`), the planner (`PLANNER_MODEL`) and the critic (`CRITIC_MODEL`). One model stays loaded on the GPU, so no turn waits for Ollama to swap models; on an 8 GB GPU that is what keeps a turn under about 10 seconds. It is also the benchmark judge's model, so both share the loaded copy during `./bench run`. The judge that picks between an agent and the critic uses `JUDGE_MODEL` (default `tev1:0.8b`). `OLLAMA_HOST` overrides the Ollama URL (default `http://localhost:11434`).

The critic debate, which writes the long-term lessons, is off by default because it adds about 3 model calls per disputed part (at most 2 per draft). `MAIL_CRITIC=1` turns it on. `MEMORY_FILE=<path>` keeps the lessons between runs; without it they are thrown away after the turn. `MAIL_DEBUG=1` prints every model answer.

## Chat page

From the project folder, in one terminal:

```bash
cd ui && npm install && npm run dev
```

Open http://127.0.0.1:5173/. That page talks to the agent on port 8787. Opening 8787 in a browser does nothing useful: it is the API, not the chat.

Only one agent can use 8787. Stop the previous one before starting another mailbox.

## Fictional mailbox

This is the default. No sign-in. The inbox is one scenario file of made-up university mail.

```bash
./ui/agent
```

That loads `benchmark/scenarios/reply-prof-01.json`. You are Léa Martin. In the chat, send:

```text
Réponds à la Prof. Dubois pour confirmer le rendez-vous qu'elle me propose.
```

The draft appears in the chat and is stored only for that scenario. It does not show up in Gmail or Outlook.

Another scenario:

```bash
SCENARIO=benchmark/scenarios/summarize-01.json ./ui/agent
```

Scenario files live in `benchmark/scenarios/`. The name of the one in use is shown in the chat header. Each start is a fresh inbox.

## Gmail

Gmail works only if the Google Cloud project is in the [Google Workspace Developer Preview](https://developers.google.com/workspace/preview). Enrollment asks for a company name and a company domain. A personal Gmail account cannot complete it, and search then fails even after the APIs are enabled.

In that project:

1. Enable the Gmail API and the Gmail MCP API.
2. Create an OAuth client. Set the authorized redirect URI to `http://127.0.0.1:8765/`. A JavaScript origin must not end with `/` and must not contain a path; the redirect URI is the field that gets the slash.
3. If the client settings offer it, turn on use of this client for an AI-powered agent. Scopes used here are read and compose. The assistant still only saves drafts.

Sign in once. The token is written to `gmail-token.json` (gitignored).

```bash
export GMAIL_CLIENT_ID="your-client-id"
export GMAIL_CLIENT_SECRET="your-client-secret"
java -cp "ui/agent-classes:benchmark/target/classes:benchmark/target/lib/*" \
  com.assistant.mail.service.mcp.GmailLogin
```

If `ui/agent-classes` does not exist yet, run `./ui/agent` once and stop it, then run the login command. Open the printed URL, sign in, and wait until the terminal says the token was saved.

Then start the agent against Gmail. Keep the same client id and secret in the environment so the token can be refreshed.

```bash
MAIL_MCP=gmail ./ui/agent
```

The agent calls `https://gmailmcp.googleapis.com` at `/mcp/v1`.

## Outlook

Uses [office-connect](https://pypi.org/project/office-connect/) 0.1.9 against a personal Microsoft account. Pin `mcp` below 2. Version 2 crashes this server on startup (`Server has no list_tools`). 1.30.0 works.

```bash
python3 -m venv .venv
.venv/bin/pip install 'office-connect==0.1.9' 'mcp<2'
```

In the Azure app registration (any tenant, personal accounts allowed):

- Platform **Mobile and desktop applications**, redirect URI `https://localhost:8443/auth/callback`. A Web redirect is not enough.
- **Allow public client flows**: Yes.

Sign in with the Microsoft account whose mailbox you want. Include `profile` or Graph `/me` fails. `drafts` keeps the assistant from sending.

```bash
.venv/bin/office-connect login \
  --client-id "your-azure-app-id" \
  --tenant-id consumers \
  --scope profile --scope mail \
  --browser
```

The browser warns about the self-signed localhost certificate. Accept it. The token is stored at `~/.config/office-connect/token.json`.

Tool names are in `outlook-tools.properties` (`o365_search_mail`, `o365_get_mail`, `o365_get_mails`, `o365_create_mail_draft`). Start the agent with that login:

```bash
MAIL_MCP=outlook \
OUTLOOK_CMD="$PWD/.venv/bin/office-connect --keyfile $HOME/.config/office-connect/token.json --permission-level drafts" \
./ui/agent
```

A reply is saved on the same thread as a draft. It is not sent.

To put the button on the Outlook page itself, keep `./ui/agent` and `npm run dev` running, then in Edge or Chrome open `edge://extensions` or `chrome://extensions`, turn on Developer mode, and choose Load unpacked. Pick `ui/extension`. Open https://outlook.live.com/mail/. A **W** button sits on that page. It opens Willow over the inbox. Hide closes it. A separate Willow tab is no longer required.

## One request from the terminal

Same mailbox variables as `./ui/agent`. Prints the answer and does not start the chat page.

```bash
java -cp "ui/agent-classes:benchmark/target/classes:benchmark/target/lib/*" \
  com.assistant.mail.workflow.turn.MailMain "summarize my last mail"
```

## Agentic organization (second system)

`com.assistant.mail.agentic` is a second, autonomous system built on the same mailbox and model:
- A router delegates the request to one specialist. Each specialist is defined in `specialists/<name>.md`: first line `description: …`, then its system prompt, then an optional `## context` section. Add a file to add an agent.
- The specialist chooses its own tool calls (search, read, thread, contact lookup).
- An opponent agent attacks every proposal. The specialist concedes or defends, for at most 2 attacks, then the coordinator commits.
- The coordinator voids proposals made before any search, or empty ones (norms).
- Lessons from conceded arguments go to `MEMORY_FILE`.

Run it on one benchmark scenario from `benchmark/` (the same environment `./bench run` provides):

```bash
SCENARIO=scenarios/reply-prof-01.json TRACE_OUT=/tmp/t.jsonl \
MAILBOX_CMD="java -cp target/classes:target/lib/* com.assistant.mail.mailbox.MailboxServer scenarios/reply-prof-01.json /tmp/tools.jsonl" \
java -cp '<agent classes>:target/classes:target/lib/*' com.assistant.mail.agentic.AgenticMain
```

Options: `OPPONENT=0` removes the opponent, `MEMORY_FILE=<path>` keeps lessons, and `MAIL_DEBUG=1` prints every agent message. Next to the trace, `<trace>.agentic.json` holds the route, the negotiation counts and the message log. `report/extract.py` turns benchmark runs into the report's plot data.

## Benchmark

The 20 fictional scenarios and the scorer are documented in `benchmark/README.md`.
