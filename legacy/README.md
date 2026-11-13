# Email Multi-Agent System

Framework multi-agents en Java pour **lire, trier, resumer et proposer des reponses** a des emails
en s'appuyant sur un **LLM local**. Le systeme fonctionne en mode *assistance* : il propose une
reponse, l'utilisateur valide, rejette ou demande une regeneration.

> Projet M2 Intelligence Artificielle - Systemes Multi-Agents.

## Architecture des agents

```
Email recu
   |
   v
[RouterAgent]  (coordinateur / dispatcher)
   |  choisit via RouterStrategy (LLM + repli mots-cles)
   v
[StudentAgent] [TeacherAgent] [AdministrationAgent] [GenericAgent]
   |  prompt systeme + contexte propres + memoire du fil
   v
[LLMClient]  (Ollama / llama.cpp / OpenRouter / mock)
   |
   v
Proposition de reponse -> utilisateur (accept / reject / regen)
```

- **RouterAgent** : ne redige pas, il classe l'email et delegue. Si la confiance est inferieure
  au seuil configure, il bascule sur le `GenericAgent`.
- **Agents specialises** : chacun possede son *system prompt* et son *contexte documentaire*.
- **LLM local** : abstraction `LLMClient` remplacable sans toucher aux agents.

## Structure du projet

```
pom.xml
context/                     contexte documentaire par agent (modifiable sans recompiler)
  student/  teacher/  administration/
src/main/resources/
  config.properties          configuration (LLM, seuils, memoire)
  prompts/*.md               prompts systeme externalises
src/main/java/fr/mas/email/
  agent/                     Agent, AbstractAgent, RouterAgent, registry, factory
  agent/specialized/         StudentAgent, TeacherAgent, AdministrationAgent, GenericAgent
  config/                    AppConfig
  context/                   ContextProvider, FileContextProvider
  llm/                       LLMClient, OpenAICompatibleClient, MockLLMClient, factory
  mail/                      MailClient, OutlookMailClient (Graph), GraphAuth, factory
  memory/                    ConversationMemory
  model/                     EmailMessage, EmailCategory, AgentResponse, RoutingDecision
  prompt/                    PromptRepository
  routing/                   RouterStrategy, LlmRouterStrategy, KeywordRouterStrategy
  system/                    EmailAssistantSystem (facade)
  console/                   ConsoleApp, SampleEmails
  Main.java
```

## Prerequis

- JDK 17+
- Maven 3.9+
- Un moteur LLM local (optionnel pour le mode mock)

## Installation d'un LLM local

### Option A - Ollama (recommande)

```bash
ollama pull llama3.1
ollama serve          # expose http://localhost:11434
```

### Option B - llama.cpp server

```bash
llama-server -m modele.gguf --port 8080
```

### Option C - OpenRouter (API distante compatible)

Renseigner dans `config.properties` :

```properties
llm.baseUrl=https://openrouter.ai/api/v1
llm.apiKey=sk-...
llm.model=meta-llama/llama-3.1-8b-instruct
```

## Configuration

Tout se fait dans `src/main/resources/config.properties` (ou par variables d'environnement,
par ex. `LLM_MODEL`, `LLM_BASEURL`).

| Cle | Description |
|-----|-------------|
| `llm.provider` | `openai` (Ollama/llama.cpp/OpenRouter) ou `mock` |
| `llm.baseUrl` | URL de l'API `/v1` |
| `llm.model` | nom du modele |
| `llm.apiKey` | cle API (vide en local) |
| `router.confidenceThreshold` | seuil de bascule vers l'agent generique |
| `memory.maxMessages` | taille de la fenetre d'historique envoyee au LLM |
| `prompts.dir` | repertoire externe de prompts (vide = classpath) |
| `context.dir` | repertoire du contexte documentaire |
| `outlook.enabled` | active l'integration Microsoft Graph |
| `outlook.authMode` | `devicecode` (interactif) ou `clientcredentials` (app-only) |
| `outlook.tenantId` / `outlook.clientId` / `outlook.clientSecret` | identifiants Azure AD |
| `outlook.mailbox` | boite ciblee (app-only) |
| `outlook.fetchLimit` | nombre d'emails recuperes |

## Compiler et lancer

```bash
mvn clean package
java -jar target/email-multiagent-1.0.0.jar                 # LLM local (Ollama) + Outlook si active
java -jar target/email-multiagent-1.0.0.jar --outlook       # avec integration Outlook (Graph)
java -jar target/email-multiagent-1.0.0.jar --mock          # sans moteur LLM (reponses simulees)
```

Via Maven (sans jar) :

```bash
mvn exec:java -Dexec.args="--outlook"
```

## Brancher l'API Outlook (Microsoft Graph)

Le framework lit et envoie les emails via **Microsoft Graph**. Il faut une *app registration*
Azure AD (gratuite avec un compte Microsoft 365 / etudiant).

### 1. Creer l'application Azure AD

1. Va sur https://portal.azure.com → **Microsoft Entra ID** → **App registrations** → **New registration**.
2. Nom : `email-multiagent`, type de compte : *Accounts in this organizational directory only*.
3. Note le **Application (client) ID** et le **Directory (tenant) ID**.
4. **Authentication** → ajoute la plateforme **Mobile and desktop applications** et coche
   `https://login.microsoftonline.com/common/oauth2/nativeclient` (necessaire pour le device code).
5. **API permissions** → **Add a permission** → **Microsoft Graph** → **Delegated permissions** :
   `Mail.ReadWrite`, `Mail.Send`, `User.Read`, `offline_access`. Puis **Grant admin consent**.
6. (Mode app-only uniquement) **Certificates & secrets** → cree un *client secret*.

### 2. Configurer `config.properties`

```properties
outlook.enabled=true
outlook.authMode=devicecode        # connexion interactive, recommandee en local
outlook.tenantId=<tenant-id>
outlook.clientId=<client-id>
```

Puis dans la console :

```
> inbox        # connexion Outlook + liste des messages
> load 1       # route et propose une reponse
> accept       # envoie la reponse dans le fil via Graph
```

Au premier `inbox`, un code et une URL de connexion s'affichent dans la console : ouvre l'URL,
saisis le code, connecte-toi. Le jeton est conserve en memoire pour la session.

### Mode app-only (sans interaction)

```properties
outlook.enabled=true
outlook.authMode=clientcredentials
outlook.tenantId=<tenant-id>
outlook.clientId=<client-id>
outlook.clientSecret=<secret>
outlook.mailbox=boite@univ.fr
```

> En mode app-only, l'application lit une boite precise sans utilisateur connecte.

## Lancer le tout avec opencode

1. **Installer les outils** : JDK 17 et Maven.

   ```bash
   brew install openjdk@17 maven
   export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
   export PATH="$JAVA_HOME/bin:$PATH"
   ```

2. **LLM local** (optionnel, sinon `--mock`) :

   ```bash
   brew install ollama
   ollama serve &
   ollama pull llama3.1
   ```

3. **Configurer** `src/main/resources/config.properties` (modele LLM + identifiants Outlook).

4. **Demander a opencode** de compiler et lancer :

   > « compile le projet Maven et lance-le avec `--outlook` »

   ou en une commande :

   ```bash
   mvn clean package && java -jar target/email-multiagent-1.0.0.jar --outlook
   ```

Pour un essai immediat sans Outlook ni LLM :

```bash
mvn clean package && java -jar target/email-multiagent-1.0.0.jar --mock
```

## Utilisation (console)

```
> agents          lister les agents
> sample          lister les emails de demonstration
> inbox           recuperer les derniers emails Outlook
> load 2          router et traiter l'email n°2
> new             saisir un email manuellement
> accept          valider la proposition (envoi Outlook si active)
> reject          rejeter
> regen trop formel, tutoie-le   regenerer avec un commentaire
> quit
```

## Ajouter un agent specialise

1. Creer une classe sous `agent/specialized/` etendant `AbstractAgent`.
2. Ajouter le prompt `src/main/resources/prompts/<id>.md`.
3. Ajouter un dossier de contexte `context/<id>/`.
4. Enregistrer l'agent dans `AgentFactory#buildRegistry()`.

Aucun autre composant du framework n'est a modifier.

## Remplacer le LLM

Implementer `LLMClient` et l'enregistrer dans `LLMClientFactory`. Les agents ne dependent que
de l'interface.

## Tests

```bash
mvn test
```

Les tests utilisent `MockLLMClient` : aucun moteur LLM n'est requis.

## Patterns utilises

| Pattern | Usage |
|---------|-------|
| Strategy | `RouterStrategy`, `LLMClient` remplacables |
| Factory | `LLMClientFactory`, `AgentFactory` |
| Template Method | `AbstractAgent#handle` |
| Adapter | `OpenAICompatibleClient` (LLM) et `OutlookMailClient` (Graph) |
| Repository | `AgentRegistry` |
| Facade | `EmailAssistantSystem` |
| Mediator | `RouterAgent` (coordination des agents) |
