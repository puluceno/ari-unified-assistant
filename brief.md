# Ari: one front door for Ripple Treasury customers

*Brief for Dana. Code and demo: github.com/puluceno/ari-unified-assistant · Test cases: `test-cases.csv` · Diagram: `docs/architecture.svg`*

## 1. The problem, as I understand it

Four teams are building four chat assistants. That is a **symptom**. The real problem: the three systems behind one
chat window have very different rules, and one chat window has to respect all of them.

| System | Its rules | If Ari gets it wrong |
|---|---|---|
| **Omni** (data and analytics) | Each customer sees only their own companies' data | If the user notices: they ask for a person or open a ticket, so it ends up with support anyway. If not: a treasurer acts on a wrong number. Highest risk |
| **Help center** (Confluence) | Some pages are for Ripple staff only | A customer sees an old or internal page |
| **Jira** (support) | Ari **creates** tickets for the customer | A ticket the customer never asked for |

So one chat window needs real work: **the same user** in every system, **routing** to the right system, **safety
checks** that every team gets, and **one log** of every decision. It is not "just plumbing".

**Is one chat window right?** One *entry point*, yes: customers should not need to know which team owns what. One
*brain*, no: my team would become every team's bottleneck, and nobody would own a wrong answer. **My team owns the
chat. Each system keeps its own access rules.** For example, Omni checks the user's permissions itself.

What I believe the brief leaves out:
- **Phase 2 has already started.** Creating a ticket changes data. So it uses the pattern that actions will need later: Ari offers, the user confirms, then Ari does it.
- **Nobody mentions the four teams.** This project has the potential to cancel other people's work. That is the hardest part (section 7).
- **"80% ticket deflection"** is easy to fake: make tickets hard to create. Also, bugs and outages always need a ticket,
  and we don't know how many tickets those are. Better main metric: **the user says the problem is solved, and does
  not come back about it within X days** (X agreed with the stakeholders). We still count tickets, but we don't target them.
- **"Answer anything"** must include "I don't know", said clearly.

## 2. Questions for Dana

| Question | What I do with the answer |
|---|---|
| What share of last quarter's tickets were "how do I...?" questions? | Mostly bugs and outages: chat can't prevent those, so 80% is not possible; I make tickets better instead. Mostly "how do I...?": I start with help-center answers |
| Did customers ask for one chat window? Which customers? | Ask the product team to get data on it, in the way they see fit: talking to customers, surveys |
| What happens to the four teams' assistants, and who decides? | A meeting with the team leads and product: do the teams own their domains and become "our clients" in this project? |
| Do we know how often Omni gives a wrong number? Who owns a wrong number today? | No: we measure it. Ask Omni questions with known answers and count the wrong ones. That decides which Omni questions we show |
| Is GPT-4o required (a security approval), or a preference? | Required: we use it. Preference: tests pick the best model for each step |
| Can the advisory board demo use test data? | No: real user permissions become a top priority. That may move the date or change the demo scope |
| What must the board see in six weeks? | A demo on test data: possible (section 4). Real customers using it: not possible in six weeks, so a smaller goal or a later date |

## 3. Approach

**Target** (diagram in the repo): Ari is a widget inside the product, so the user is already logged in. It sends each
question, with who the user is, to the **hub**. The hub checks the user, checks the question, routes it, checks the
answer, and logs every step. Behind it, each **domain agent** follows one contract: given a question and a user, return
the data, what was measured, the source and the time. Models sit behind a **gateway**, so we can change them by configuration.

**The main rule: the model suggests, code decides.** The model labels the question (where it should go, how sure it
is, why) and writes the answer. A plain `switch` in code decides what happens: ask the user to clarify, decline an
action, offer a ticket, or call a domain. So anyone can read and audit the routing, and **test it without a model**.

**The demo** (Java 25, Spring Boot, behind the Open WebUI chat app) builds that slice: two read systems plus ticket
creation, all three mocked behind the contract. The model call is real (Azure OpenAI; switching to GPT-4o is one
configuration value). Every answer shows how Ari decided. Adding a system is one new class; the router does not change.

**Weak points of this shape:** it assumes a logged-in customer (a public help site would get help articles only).
Each answer needs two model calls, so it is slower and costs more. One question goes to one system only. The number
check cannot see if Omni ran the wrong query. And if the hub is down, Ari is down for everyone.

## 4. Six weeks: what I'd show at the advisory board

Six weeks is short for a new team. Real safety checks and real testing take time, so I'd show less, and show it working.

**In six weeks** (test data only, one product page): help-center answers with a link to the article; tickets (Ari
offers, the user says yes, the conversation is attached); Omni answers for about ten common questions (cash, FX
exposure), with the source and time on every number; the number check and the decision log from the demo. Shown as a
**preview**: no release date, no actions promised.

**Not in six weeks:** real customer data; actions (approving payments, submitting forecasts); forecasting and
onboarding; more product pages; "ask anything"; full test automation and monitoring.

## 5. How I'd know it's working

**After six weeks:** a test set of real questions from the four teams, each with the right answer written down, enough
to cover each type of question a few times. It runs on every change to a prompt or model, and tracks three things:
did it go to the right system, is the number right, and did Ari say "I don't know" when it should. Users give thumbs up
or thumbs down on every answer. Every decision is logged for our team (users don't see it), so we can see why an
answer went wrong.

**Later in phase 1:** the tests block the release if they fail. A domain joins Ari only after it passes them, plus
security tests (prompt injection, one customer reading another's data). In production: a few known questions once a
day against a test account, to catch changes in Omni we don't control; a "this is wrong" button that goes to the
domain team; a ticket right after an answer counts as a probably wrong answer; people review a sample of answers; a
new model runs in the background before we switch to it.

## 6. What worries me

- **Omni.** It is the highest risk, and nobody can tell me how often it reads a question wrong. A wrong number looks
  exactly like a right one. So **Ari never retypes numbers** (if a number in the answer is not in Omni's data, Ari
  shows the raw data instead), and **every number shows** what was measured, its source and its time, so users can
  catch a mistake themselves. We can't catch everything, but we can make it checkable.
- **One customer seeing another's data.** The model never decides who sees what. Each system checks the user's permissions.
- **Prompt injection.** The model has no power: it only reads, and a ticket needs the user's "yes".
- **Six weeks growing into "ask anything".** Answer: a clear scope for each release, and a prioritised roadmap.

## 7. The four teams, and ownership

Before building, I visit each team: what did you build, what worked? Show me your real questions and logs. **Each team
becomes the owner of its domain agent** behind the hub, with its own test questions. A domain joins after passing our
tests, for example: "What is my EUR exposure?" returns Omni's exact number, and a Globex user never gets Acme's data.
My team owns the chat, login, routing, safety checks, logs and testing tools. Domain teams own whether their answers
are correct. These teams don't report to me, so first I'd ask Dana: which senior leader backs this project, and who
makes the final decision when teams disagree?

**The team:** first see who is already there, and whether people in other teams want to join this effort. Then hire,
if we need to.

## 8. What the demo does not do, and what I found by testing it

**Not built:** real user permissions (the demo trusts the chat app's user header, protected by an API key),
word-by-word streaming, questions that need two systems.

**What I found by testing it:**
- "My EUR exposure looks too low; how do I connect a missing bank account?" needs Omni and the help center. Ari picks
  one, so it gives half an answer.
- "What was the EUR/USD rate at close yesterday?" goes to Omni with 0.67 confidence. There is no "not supported"
  option, and the 0.6 limit is a guess.
- "What is my EUR exposure?" then "and GBP?": Ari asks what I mean, because it only reads the last message.

**Next two weeks:** polish the code; add a new system by configuration instead of new code; start the test set of
real questions; start real user permissions; handle questions that fit two systems, or none.
