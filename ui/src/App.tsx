import { useEffect, useRef, useState } from "react";
import AgentsPanel from "./AgentsPanel";

const asset = (name: string) => `/assets/${name}`;

function Icon({
  name,
  size = 18,
  className = "",
}: {
  name: string;
  size?: number;
  className?: string;
}) {
  return (
    <img
      aria-hidden="true"
      className={`icon ${className}`}
      src={asset(name)}
      width={size}
      height={size}
      alt=""
    />
  );
}

type Focus = "header" | "conversation" | "draft";

type Point = { title: string; detail?: string };

type DraftView = {
  to: string;
  cc: string;
  bcc: string;
  subject: string;
  body: string;
  tone: string;
  link?: string;
};

type Rule = {
  index: number;
  text: string;
  reason: string;
  agent: string;
  vetoed: boolean;
  fresh: boolean;
};

type Answer = {
  text: string;
  summary?: string;
  points?: Point[];
  sources?: string[];
  questions?: string[];
  refused?: string[];
  chats?: string[];
  draft?: DraftView;
  rules?: Rule[];
  agent?: string;
  context?: string[];
  error?: string;
};

type Status = { mailbox: string; scenario: string; model: string };

type TurnView = { user: string; answer: Answer | null; error?: string };

const emptyDraft: DraftView = { to: "", cc: "", bcc: "", subject: "", body: "", tone: "" };

function Reply({ answer }: { answer: Answer }) {
  return (
    <>
      {answer.agent && (
        <div className="agent-badge">
          <span>
            Routed to <strong>{answer.agent}</strong> · experimental
          </span>
          {!!answer.context?.length && (
            <details>
              <summary>Context used ({answer.context.length})</summary>
              <ul>
                {answer.context.map((passage, index) => (
                  <li key={index}>{passage}</li>
                ))}
              </ul>
            </details>
          )}
        </div>
      )}
      {answer.summary && (
        <div className="message-bubble system-message summary-request">{answer.summary}</div>
      )}
      {!!answer.points?.length && (
        <>
          <div className="matters-title">
            <Icon name="ff497.svg" size={22} />
            <h2>Here’s what matters</h2>
          </div>
          <ol className="matter-list">
            {answer.points.map((point, index) => (
              <li key={`${index}-${point.title}`}>
                <span className="matter-number">{String(index + 1).padStart(2, "0")}</span>
                <div>
                  <strong>{point.title}</strong>
                  {point.detail && <p>{point.detail}</p>}
                </div>
              </li>
            ))}
          </ol>
        </>
      )}
      {!!answer.sources?.length && (
        <div className="sources">
          {answer.sources.map((source) => (
            <span key={source}>
              <Icon name="8aac9.svg" size={16} />
              {source}
            </span>
          ))}
        </div>
      )}
      {answer.chats?.map((line, index) => (
        <div className="message-bubble system-message draft-request" key={`chat-${index}`}>
          {line}
        </div>
      ))}
      {answer.questions?.map((question, index) => (
        <div className="message-bubble system-message draft-request" key={`q-${index}`}>
          {question}
        </div>
      ))}
      {answer.refused?.map((line, index) => (
        <div className="message-bubble system-message draft-request" key={`r-${index}`}>
          {line}
        </div>
      ))}
      {answer.draft && (
        <div className="done-row">
          <Icon name="f0161.svg" size={20} />
          <div>
            <strong>Done. Your reply is saved to Drafts.</strong>
            <p>Review it here. Nothing has been sent.</p>
          </div>
        </div>
      )}
    </>
  );
}

function paragraphs(body: string) {
  return body.split(/\n{2,}/).filter((part) => part.trim());
}

export default function App() {
  const [focus, setFocus] = useState<Focus>("draft");
  const [ccOpen, setCcOpen] = useState(false);
  const [rules, setRules] = useState<Rule[]>([]);
  const [message, setMessage] = useState("");
  const [notice, setNotice] = useState("Nothing is sent. A reply is saved as a draft.");
  const embed = new URLSearchParams(window.location.search).has("embed");
  const [mode, setMode] = useState<"button" | "panel" | "full">(embed ? "panel" : "button");
  const [busy, setBusy] = useState(false);
  const [agentMode, setAgentMode] = useState(false); // experimental: router + specialists instead of the workflow
  const [agentsOpen, setAgentsOpen] = useState(false);
  const [steps, setSteps] = useState<string[]>([]);
  const [showSteps, setShowSteps] = useState(true);
  const [turns, setTurns] = useState<TurnView[]>([]);
  const [upload, setUpload] = useState<{ name: string; text: string } | null>(null);
  const fileRef = useRef<HTMLInputElement>(null);
  const [status, setStatus] = useState<Status>({
    mailbox: "university",
    scenario: "",
    model: "",
  });

  useEffect(() => {
    fetch("/api/status")
      .then((response) => (response.ok ? response.json() : null))
      .then((body: Status | null) => {
        if (body) setStatus(body);
        else setNotice("Start the agent with ./ui/agent, then reload.");
      })
      .catch(() => setNotice("Start the agent with ./ui/agent, then reload."));
    fetch("/api/memory")
      .then((response) => (response.ok ? response.json() : null))
      .then((body: { rules?: Rule[] } | null) => {
        if (body?.rules) setRules(body.rules);
      })
      .catch(() => undefined);
  }, []);

  const draft =
    [...turns].reverse().find((turn) => turn.answer?.draft)?.answer?.draft ?? emptyDraft;
  const scenarioName = status.scenario.split("/").pop()?.replace(".json", "") ?? "";
  const threadLabel = status.mailbox === "university" && scenarioName ? scenarioName : status.mailbox;

  useEffect(() => {
    if (!busy || !showSteps) return;
    let stop = false;
    const tick = () => {
      fetch("/api/progress")
        .then((response) => (response.ok ? response.json() : null))
        .then((body: { lines?: string[] } | null) => {
          if (!stop && body?.lines) setSteps(body.lines);
        })
        .catch(() => undefined);
    };
    tick();
    const id = window.setInterval(tick, 800);
    return () => {
      stop = true;
      window.clearInterval(id);
    };
  }, [busy, showSteps]);

  const ask = async (text: string) => {
    const request = text.trim();
    if (!request || busy) return;
    const history = turns
      .filter((turn) => turn.answer || turn.error)
      .slice(-4)
      .map((turn) => {
        const reply =
          turn.error ||
          turn.answer?.summary ||
          turn.answer?.chats?.join(" ") ||
          turn.answer?.questions?.join(" ") ||
          turn.answer?.refused?.join(" ") ||
          "";
        return `User: ${turn.user}\nAssistant: ${reply.slice(0, 400)}`;
      })
      .join("\n");
    setTurns((items) => [...items, { user: request, answer: null }]);
    setSteps([]);
    setMessage("");
    setBusy(true);
    setNotice("Working on the mailbox…");
    try {
      const response = await fetch(agentMode ? "/api/agentic/turn" : "/api/turn", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          message: request,
          history,
          files: upload ? [{ name: upload.name, text: upload.text }] : [],
        }),
      });
      const body = (await response.json()) as Answer;
      if (!response.ok || body.error) {
        const error = body.error || "The turn failed.";
        setTurns((items) =>
          items.map((turn, index) => (index === items.length - 1 ? { ...turn, error } : turn)),
        );
        setNotice(error);
        return;
      }
      setTurns((items) =>
        items.map((turn, index) => (index === items.length - 1 ? { ...turn, answer: body } : turn)),
      );
      if (body.rules) setRules(body.rules);
      setNotice(
        body.draft
          ? "Draft saved. Nothing sent."
          : body.chats?.length
            ? "Done."
            : body.questions?.length
            ? "The agent asked a question. Nothing was drafted."
            : "Done. Nothing sent.",
      );
      setUpload(null);
    } catch {
      setTurns((items) =>
        items.map((turn, index) =>
          index === items.length - 1
            ? { ...turn, error: "The agent server is not running. Start it with ./ui/agent." }
            : turn,
        ),
      );
      setNotice("The agent server is not running. Start it with ./ui/agent.");
    } finally {
      try {
        const progress = await fetch("/api/progress");
        if (progress.ok) {
          const body = (await progress.json()) as { lines?: string[] };
          if (body.lines) setSteps(body.lines);
        }
      } catch {
        /* the turn result still stands */
      }
      setBusy(false);
    }
  };

  const veto = async (index: number) => {
    const response = await fetch("/api/veto", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ index }),
    }).catch(() => null);
    if (!response?.ok) {
      setNotice("Could not veto that lesson.");
      return;
    }
    const body = (await response.json()) as { rules?: Rule[] };
    if (body.rules) setRules(body.rules);
    setNotice("Vetoed. That lesson will not be used again.");
  };

  const reset = async () => {
    await fetch("/api/reset", { method: "POST" }).catch(() => undefined);
    setTurns([]);
    setSteps([]);
    setNotice("New conversation. Nothing sent.");
  };

  const setView = (next: "button" | "panel" | "full") => {
    if (embed) {
      const type = next === "button" ? "willow-hide" : next === "full" ? "willow-full" : "willow-panel";
      window.parent.postMessage({ type }, "*");
      if (next === "button") return;
    }
    setMode(next);
  };

  return (
    <main className={`app is-${mode}${embed ? " is-embed" : ""}`}>
      {mode === "button" && (
        <button type="button" className="launcher" onClick={() => setView("panel")} aria-label="Open Willow">
          <span className="brand-mark">
            <Icon name="06a24.svg" size={36} />
            <Icon name="2ad7f.svg" size={24} className="brand-envelope" />
          </span>
        </button>
      )}
      {mode !== "button" && (
      <div className="workspace">
        <div className="float-bar">
          <span>{status.mailbox}</span>
          <button type="button" onClick={reset}>New</button>
          <button
            type="button"
            onClick={() => setAgentMode(!agentMode)}
            aria-pressed={agentMode}
            title="Workflow: planner and writer. Agents: router, specialists and an opponent (experimental)."
          >
            {agentMode ? "Mode: Agents" : "Mode: Workflow"}
          </button>
          <button type="button" onClick={() => setAgentsOpen(true)}>Edit agents</button>
          <button type="button" onClick={() => setView(mode === "full" ? "panel" : "full")}>
            {mode === "full" ? "Float" : "Full screen"}
          </button>
          {mode === "panel" && (
            <button type="button" onClick={() => setView("button")} aria-label="Hide Willow">
              Hide
            </button>
          )}
        </div>
        <section
          className={`panel-wrap conversation-wrap ${focus === "conversation" ? "is-focused" : ""}`}
          onMouseEnter={() => setFocus("conversation")}
          onMouseLeave={() => setFocus("header")}
          aria-label="Conversation with Willow"
        >
          <div className="panel conversation-panel">
            <div className="context-card">
              <span className="context-title">
                <Icon name="e07c1.svg" size={18} />
                {threadLabel}
              </span>
              <button type="button" className="steps-toggle" onClick={() => setShowSteps(!showSteps)}>
                {showSteps ? "Hide steps" : "Show step"}
              </button>
            </div>

            {turns.map((turn, index) => (
              <div className="turn" key={`${index}-${turn.user}`}>
                <div className="message-bubble user-message summary-request">{turn.user}</div>
                {showSteps && busy && index === turns.length - 1 && steps.length > 0 && (
                  <p className="progress-now" aria-live="polite">
                    {steps[steps.length - 1]}
                  </p>
                )}
                {turn.error && (
                  <div className="message-bubble system-message draft-request">{turn.error}</div>
                )}
                {turn.answer && <Reply answer={turn.answer} />}
              </div>
            ))}

            <form
              className="conversation-composer"
              onSubmit={(event) => {
                event.preventDefault();
                void ask(message);
              }}
            >
              <input
                value={message}
                onChange={(event) => setMessage(event.target.value)}
                placeholder={agentMode ? "Ask the agents about this mailbox…" : "Ask about this mailbox…"}
                aria-label="Ask about this mailbox"
                disabled={busy}
              />
              <span className="thread-label">
                <Icon name="23572.svg" size={16} />
                {upload ? upload.name : threadLabel}
              </span>
              <input
                ref={fileRef}
                className="file-input"
                type="file"
                onChange={(event) => {
                  const file = event.target.files?.[0];
                  event.target.value = "";
                  if (!file) return;
                  void file.text().then((text) => setUpload({ name: file.name, text: text.slice(0, 20000) }));
                }}
              />
              <button
                className="composer-file"
                type="button"
                aria-label="Upload a file"
                disabled={busy}
                onClick={() => fileRef.current?.click()}
              >
                <Icon name="87083.svg" size={18} />
              </button>
              <button aria-label="Send message" type="submit" disabled={busy}>
                <Icon name="87cc2.svg" size={18} />
              </button>
            </form>
          </div>
        </section>

        <section
          className={`panel-wrap draft-wrap ${focus === "draft" ? "is-focused" : ""}`}
          onMouseEnter={() => setFocus("draft")}
          aria-label="Email draft editor"
        >
          <div className="panel draft-panel">
            <div className="draft-heading">
              <span>YOUR DRAFT</span>
              {draft.body && (
                <span className="saved-badge">
                  <Icon name="73f5a.svg" size={15} />
                  Saved to mail drafts
                </span>
              )}
            </div>

            <article className="email-editor">
              <div className="field-row">
                <span>To</span>
                <strong>{draft.to || "—"}</strong>
                <button type="button" onClick={() => setCcOpen(!ccOpen)}>
                  Cc / Bcc
                </button>
              </div>
              {ccOpen && (
                <div className="field-row subject-row">
                  <span>Cc</span>
                  <strong>{[draft.cc, draft.bcc].filter(Boolean).join(" · ") || "—"}</strong>
                </div>
              )}
              <div className="field-row subject-row">
                <span>Subject</span>
                <strong>{draft.subject || "—"}</strong>
              </div>

              <div key={draft.body} className="email-body" aria-label="Draft email content">
                {draft.body
                  ? paragraphs(draft.body).map((part) => <p key={part}>{part}</p>)
                  : <p>No draft yet. Ask in the conversation.</p>}
              </div>
            </article>

            <div className="memory-area">
              <h3>LESSONS TO KEEP</h3>
              {rules.length === 0 ? (
                <p className="memory-empty">No lesson yet. After a turn, a rule the critic kept shows up here.</p>
              ) : (
                <ul className="memory-list">
                  {[...rules].reverse().map((rule) => (
                    <li key={rule.index} className={rule.vetoed ? "is-vetoed" : ""}>
                      {rule.fresh && !rule.vetoed && <span className="memory-new">New</span>}
                      <p>{rule.text}</p>
                      {rule.reason && <small>{rule.reason}</small>}
                      {rule.agent && <small>{rule.agent.toLowerCase()}</small>}
                      {rule.vetoed ? (
                        <span className="memory-vetoed">Vetoed</span>
                      ) : (
                        <button type="button" onClick={() => void veto(rule.index)}>
                          Veto
                        </button>
                      )}
                    </li>
                  ))}
                </ul>
              )}
            </div>

            <div className="draft-footer">
              <span className="autosave">
                <Icon name="beb45.svg" size={18} />
                {notice}
              </span>
              {draft.link ? (
                <a className="open-draft" href={draft.link} target="_blank" rel="noreferrer">
                  Open draft in mail
                  <Icon name="48f0e.svg" size={16} />
                </a>
              ) : (
                <button type="button" className="open-draft" disabled>
                  {draft.body ? "No mail link for this draft" : "Open draft in mail"}
                </button>
              )}
            </div>
          </div>
        </section>
      </div>
      )}
      {agentsOpen && <AgentsPanel onClose={() => setAgentsOpen(false)} />}
    </main>
  );
}
