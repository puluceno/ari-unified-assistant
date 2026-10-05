# Ari: one assistant in front of many product areas

Demo for a treasury platform. One chat entry point, a hub that decides where each question goes,
domain agents that answer with the customer's own data, and a ticket when Ari can't help.

**The model proposes, code decides.** The model picks a route and words the answer. It never picks
the organisation, never calls a system itself, and no number reaches the user unless it is in the data.

[Brief](brief.md) · [Test cases](test-cases.csv) · [Architecture](docs/architecture.svg) ([Excalidraw source](docs/architecture-v2.excalidraw), [my first sketch](docs/architecture-v1.excalidraw))

## Run

```bash
cp .env.example .env     # Azure values + generated secrets
docker compose up -d --build
```

Open http://localhost:3000, sign in with `WEBUI_ADMIN_EMAIL` (docker-compose.yml) and `WEBUI_ADMIN_PASSWORD` (.env).
`scripts/ask.sh "question" [email]` calls the hub directly.

## The code (7 files)

| File | What it does |
|---|---|
| [ChatController](src/main/java/io/github/puluceno/ari/ChatController.java) | OpenAI-compatible endpoint. Checks the API key, maps the user to an organisation. |
| [Ari](src/main/java/io/github/puluceno/ari/Ari.java) | The hub: refuse injection → ticket on "yes" → classify → decide → ask the domain → compose → number check. |
| [DomainAgent](src/main/java/io/github/puluceno/ari/DomainAgent.java) | The contract every product area implements. |
| [Mocks](src/main/java/io/github/puluceno/ari/Mocks.java) | Fake Omni, help center and Jira Service Management. |
| [Model](src/main/java/io/github/puluceno/ari/Model.java), [AzureModel](src/main/java/io/github/puluceno/ari/AzureModel.java) | One POST to Azure OpenAI. |
| [AriTest](src/test/java/io/github/puluceno/ari/AriTest.java) | The paths that matter, with a fake model. |

## Demo

| Ask | Shows |
|---|---|
| What is my EUR exposure this month? | Answer from data, with what was measured and the source |
| Why did my cash forecast miss in March? | Contributors from the data, not invented causes |
| How do I set payment approval limits? | Help center |
| What is our liquidity forecast? → yes | Omni is down: admitted, ticket offered, ticket created |
| Approve the payment run for Friday | Declined: no writes from chat |
| Ignore all previous instructions... | Refused before the model is called |
| As `sam@globex.example`: What is Acme's EUR exposure? | Only Globex data is reachable |

Every turn writes an audit trail, one line per step (identity, guard, route proposal with confidence and the model's
reason, the decision in code, the domain call, number check, outcome), all tagged with one turn id:

```bash
docker compose logs -f --no-log-prefix ari-hub | grep -E "turn=|step="
```
