# MAS Project

Assistant mail multi-agents (M2 IA – Systèmes Multi-Agents) : un **Planner** découpe la demande, un
**Writer** rédige les brouillons, un **Summarizer** résume. Les brouillons sont enregistrés, **jamais envoyés**
par l'assistant.

| Dossier | Contenu |
|---|---|
| `assistant-core/` | Le cœur (`com.assistant.mail`, Java 21) : Planner, Writer, Summary, mémoire, traces. Contient aussi le client Ollama, la boîte mail (MCP ou démo), le terminal, le serveur web et le runner du benchmark. |
| `email-multiagent/` | Application complète (Java, IMAP/SMTP, agent de sécurité, routeur, RAG, audit, UI web). Embarque `assistant-core` : le Planner est disponible sur `/assistant.html`. |
| `benchmark/` | Benchmark de 20 scénarios sur une boîte mail fictive (MCP), noté couche par couche. |
| `legacy/` | Première version (Outlook/Graph) et énoncé du projet. |
| `devpost/` | PRD, spec, checklist du hackathon. |

## Lancer

Prérequis : JDK 21+ et Maven (`./run.sh` trouve tout seul un JDK 21+, même si `java` est ancien).
Pour un vrai modèle : Ollama (`ollama serve`) et un modèle qui suit le format des prompts
(`ollama pull llama3.1:8b`, celui du benchmark). Sans modèle : `--mock` (réponses scriptées, sert à voir le flux).

```bash
./run.sh cli --mock                  # terminal uniquement
./run.sh cli --model llama3.1:8b     # terminal avec Ollama
./run.sh web --mock                  # chat dans le navigateur : http://localhost:8090 (+ API JSON)
./run.sh app                         # application complète : http://localhost:8080 (+ /assistant.html)
./run.sh bench check                 # benchmark : lint | check | run | diff | compare | show
./run.sh bench-run                   # fait passer le vrai assistant dans les 20 scénarios
./run.sh test                        # tous les tests (assistant-core, email-multiagent)
```

Options de `cli` / `web` / `bench-run` : `--mock`, `--model NOM`, `--ollama-url URL`, `--mcp-mailbox`
(boîte lue via `MAILBOX_CMD`), `--port N`. Variables : `OLLAMA_HOST`, `ASSISTANT_MODEL`, `MAILBOX_CMD`.

Dans le terminal : `/help`, `/trace` (étapes planner → rédacteur), `/drafts`, `/inbox`, `/quit`.
API JSON : `POST /api/assistant {"text": "Réponds à Ada"}`, `GET /api/drafts`, `GET /api/health`.

`./run.sh app` lit votre boîte IMAP : renseignez `imap.*` / `smtp.*` (mot de passe d'application) dans
`email-multiagent/email-core/src/main/resources/config.properties`, **sans le committer**.

Si le modèle demandé n'est pas installé, l'assistant choisit seul un modèle installé qui suit bien les
consignes (famille llama3.1, qwen3, qwen2.5…) et le dit ; les petits modèles (≤1b) sont écartés.
`ASSISTANT_DEBUG=1` affiche chaque appel au modèle sur stderr. Les écarts de format courants des modèles
(appel d'outil écrit `search_mail("Ada")`, champs sur une ligne, faits recopiés du gabarit) sont corrigés
sans changer le contenu (`TolerantReplies`).

## État

- Compilent et sont testés : les trois modules (`./run.sh test`).
- Vérifié avec un vrai modèle local (`qwen3-abliterated:4b`) : résumé, brouillon de réponse, petite conversation.
- Le benchmark réel (`bench-run`) demande le modèle juge `llama3.1:8b` (`ollama pull llama3.1:8b`).
- Un petit modèle qui ne respecte pas le format des prompts ne produit rien d'exploitable : l'assistant
  le signale au lieu de rester muet.
