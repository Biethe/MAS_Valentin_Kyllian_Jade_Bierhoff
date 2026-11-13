import { useCallback, useEffect, useState } from "react";

type AgentFile = { name: string; size: number };

type Agent = {
  name: string;
  description: string;
  prompt: string;
  context: string;
  files: AgentFile[];
};

type FileEditor = { name: string; text: string; isNew: boolean };

async function api<T>(path: string, method = "GET", body?: unknown): Promise<T> {
  const response = await fetch(path, {
    method,
    headers: body === undefined ? undefined : { "Content-Type": "application/json" },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await response.text();
  const data = text ? JSON.parse(text) : {};
  if (!response.ok) throw new Error(data.error || `Request failed (${response.status})`);
  return data as T;
}

/** Create and edit the specialist agents: description, system prompt, built-in context and context files. */
export default function AgentsPanel({ onClose }: { onClose: () => void }) {
  const [agents, setAgents] = useState<Agent[]>([]);
  const [retrieval, setRetrieval] = useState(true);
  const [selected, setSelected] = useState("");
  const [description, setDescription] = useState("");
  const [prompt, setPrompt] = useState("");
  const [context, setContext] = useState("");
  const [file, setFile] = useState<FileEditor | null>(null);
  const [creating, setCreating] = useState(false);
  const [newName, setNewName] = useState("");
  const [status, setStatus] = useState("");
  const [failed, setFailed] = useState(false);

  const say = (text: string, bad = false) => {
    setStatus(text);
    setFailed(bad);
  };

  const pick = (list: Agent[], name: string) => {
    const agent = list.find((item) => item.name === name) ?? list[0];
    setSelected(agent?.name ?? "");
    setDescription(agent?.description ?? "");
    setPrompt(agent?.prompt ?? "");
    setContext(agent?.context ?? "");
    setFile(null);
  };

  const load = useCallback(async (keep?: string) => {
    try {
      const body = await api<{ agents: Agent[]; retrieval: boolean }>("/api/agents");
      setAgents(body.agents);
      setRetrieval(body.retrieval);
      pick(body.agents, keep ?? "");
    } catch (error) {
      say(`Could not load the agents. Start the agent with ./ui/agent. ${(error as Error).message}`, true);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  const current = agents.find((agent) => agent.name === selected);

  const save = async () => {
    try {
      await api(`/api/agents/${selected}`, "PUT", { description, prompt, context });
      say("Saved. It applies to your next request.");
      await load(selected);
    } catch (error) {
      say((error as Error).message, true);
    }
  };

  const create = async () => {
    try {
      await api("/api/agents", "POST", { name: newName.trim(), description, prompt });
      say(`Agent ${newName.trim()} created.`);
      setCreating(false);
      const name = newName.trim();
      setNewName("");
      await load(name);
    } catch (error) {
      say((error as Error).message, true);
    }
  };

  const openFile = async (name: string) => {
    try {
      const body = await api<{ name: string; text: string }>(`/api/agents/${selected}/files/${encodeURIComponent(name)}`);
      setFile({ name: body.name, text: body.text, isNew: false });
      say("");
    } catch (error) {
      say((error as Error).message, true);
    }
  };

  const saveFile = async () => {
    if (!file) return;
    try {
      await api(`/api/agents/${selected}/files/${encodeURIComponent(file.name.trim())}`, "PUT", { text: file.text });
      say(`${file.name.trim()} saved.`);
      const name = file.name.trim();
      await load(selected);
      await openFile(name);
    } catch (error) {
      say((error as Error).message, true);
    }
  };

  const deleteFile = async () => {
    if (!file || file.isNew) return;
    try {
      await api(`/api/agents/${selected}/files/${encodeURIComponent(file.name)}`, "DELETE");
      say(`${file.name} deleted.`);
      await load(selected);
    } catch (error) {
      say((error as Error).message, true);
    }
  };

  const importFile = async (chosen: File | undefined) => {
    if (!chosen) return;
    const text = await chosen.text();
    setFile({ name: chosen.name, text: text.slice(0, 100000), isNew: true });
  };

  return (
    <div className="agents-overlay" role="dialog" aria-label="Agents">
      <div className="agents-panel">
        <header className="agents-head">
          <div>
            <h2>Agents</h2>
            <p>
              Experimental. Each agent has its own prompt and its own documents. A change applies to your next request in
              Agents mode.
            </p>
          </div>
          <button type="button" onClick={onClose} aria-label="Close the agents panel">
            Close
          </button>
        </header>

        {!retrieval && (
          <p className="agents-warning">
            Document retrieval is off: the server was started without CONTEXT_DIR. Start it with ./ui/agent.
          </p>
        )}

        <div className="agents-body">
          <nav className="agents-list" aria-label="Agents">
            {agents.map((agent) => (
              <button
                key={agent.name}
                type="button"
                className={agent.name === selected && !creating ? "is-selected" : ""}
                onClick={() => {
                  setCreating(false);
                  pick(agents, agent.name);
                }}
              >
                <strong>{agent.name}</strong>
                <small>{agent.files.length} file(s)</small>
              </button>
            ))}
            <button
              type="button"
              className={`agents-new ${creating ? "is-selected" : ""}`}
              onClick={() => {
                setCreating(true);
                setDescription("");
                setPrompt("");
                setFile(null);
              }}
            >
              + New agent
            </button>
          </nav>

          <div className="agents-edit">
            {creating ? (
              <>
                <label>
                  Name
                  <input
                    value={newName}
                    onChange={(event) => setNewName(event.target.value)}
                    placeholder="legal (a-z, 0-9, - or _)"
                  />
                </label>
                <label>
                  Description (the router reads this line)
                  <input value={description} onChange={(event) => setDescription(event.target.value)} />
                </label>
                <label>
                  System prompt
                  <textarea rows={8} value={prompt} onChange={(event) => setPrompt(event.target.value)} />
                </label>
                <div className="agents-actions">
                  <button type="button" onClick={() => void create()} disabled={!newName.trim() || !description.trim()}>
                    Create agent
                  </button>
                  <button type="button" onClick={() => { setCreating(false); pick(agents, selected); }}>
                    Cancel
                  </button>
                </div>
              </>
            ) : current ? (
              <>
                <label>
                  Description (the router reads this line)
                  <input value={description} onChange={(event) => setDescription(event.target.value)} />
                </label>
                <label>
                  System prompt
                  <textarea rows={8} value={prompt} onChange={(event) => setPrompt(event.target.value)} />
                </label>
                <label>
                  Built-in context (always given to this agent)
                  <textarea rows={3} value={context} onChange={(event) => setContext(event.target.value)} />
                </label>
                <div className="agents-actions">
                  <button type="button" onClick={() => void save()}>
                    Save agent
                  </button>
                </div>

                <h3>Context documents</h3>
                <p className="agents-hint">
                  Only this agent receives them. The closest passages to your request are added to its prompt.
                </p>
                <ul className="agents-files">
                  {current.files.map((item) => (
                    <li key={item.name}>
                      <button type="button" onClick={() => void openFile(item.name)}>
                        {item.name}
                      </button>
                      <small>{item.size} B</small>
                    </li>
                  ))}
                  {current.files.length === 0 && <li className="agents-hint">No document yet.</li>}
                </ul>
                <div className="agents-actions">
                  <button type="button" onClick={() => setFile({ name: "notes.txt", text: "", isNew: true })}>
                    New document
                  </button>
                  <label className="agents-import">
                    Import a text file
                    <input
                      type="file"
                      accept=".txt,.md,text/plain,text/markdown"
                      onChange={(event) => {
                        const chosen = event.target.files?.[0];
                        event.target.value = "";
                        void importFile(chosen);
                      }}
                    />
                  </label>
                </div>
                {file && (
                  <div className="agents-file-editor">
                    <label>
                      File name (.txt or .md)
                      <input
                        value={file.name}
                        disabled={!file.isNew}
                        onChange={(event) => setFile({ ...file, name: event.target.value })}
                      />
                    </label>
                    <textarea
                      rows={8}
                      aria-label="Document text"
                      value={file.text}
                      onChange={(event) => setFile({ ...file, text: event.target.value })}
                    />
                    <div className="agents-actions">
                      <button type="button" onClick={() => void saveFile()}>
                        Save document
                      </button>
                      {!file.isNew && (
                        <button type="button" onClick={() => void deleteFile()}>
                          Delete document
                        </button>
                      )}
                      <button type="button" onClick={() => setFile(null)}>
                        Close
                      </button>
                    </div>
                  </div>
                )}
              </>
            ) : (
              <p className="agents-hint">No agent found.</p>
            )}
          </div>
        </div>

        {status && (
          <p className={`agents-status ${failed ? "is-error" : ""}`} aria-live="polite">
            {status}
          </p>
        )}
      </div>
    </div>
  );
}
