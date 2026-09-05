# Architecture Treatment: AI Strategy & Integration for Workforce Intelligence

**Document Version:** 1.0  
**Status:** Approved Architectural Roadmap  
**Target Product:** Workforce Management & Headcount Intelligence Platform (`workforce`)  
**Underlying Stack:** Clojure, Red Planet Labs Rama, Replicant / Fulcro, Pathom 3, Model Context Protocol (MCP), Escapement, Agent-o-Rama  

---

## 1. Executive Summary & Market Context

The Headcount Management and Workforce Intelligence segment occupies a critical intersection between **FinTech** (corporate FP&A, payroll budgets, currency exchange, loaded labor factors) and **HRTech** (HRIS hierarchies, ATS job requisitions, performance leveling).

Platforms in this category (e.g., TeamOhana, ChartHop) manage millions of dollars in corporate capital, strict legal compliance boundaries, and sensitive personal salary data. Consequently, **pure "headless" or "AI-native chat" approaches completely fail in this space**:
1. **Mathematical Ground Truth Cannot Be Delegated to an LLM**: Hallucinated salary bands, mangled headcount counts, or unverified budget overruns create unacceptable corporate liabilities.
2. **Cognitive Bandwidth Favors Visual Canvases**: Executives and managers do not want to parse 500-word conversational summaries or raw markdown tables to understand an organization. They require an immediate, interactive, and spatial **Org Chart Canvas**.
3. **High-Stakes Operations Require Deterministic Guardrails**: A $200,000 headcount approval cannot be triggered blindly by an LLM prompt; it requires authenticated human-in-the-loop validation, strict Role/Attribute-Based Access Control (RBAC/ABAC), and immutable audit logs.

The winning architecture is therefore an **AI-Assisted Canvas**:
- **Deterministic Core**: An immutable, event-sourced database providing sub-millisecond aggregations and ground truth (Rama).
- **Interactive Visual Canvas**: A spatial frontend for hierarchy exploration, drag-and-drop structural modeling, and explicit confirmation actions (Fulcro + Replicant).
- **Agentic Operational Layer**: An autonomous and assistive AI layer that operates through typed protocols (MCP), state machine workflows (Escapement), and durable event-sourced execution (Agent-o-Rama).

---

## 2. The Architectural Trinity: Data, Canvas, and AI

```
 ┌─────────────────────────────────────────────────────────────────────────┐
 │                       VISUAL CANVAS LAYER (UI)                          │
 │  • Spatial Org Hierarchy (Pan/Zoom, Avatars, Card Virtualization)       │
 │  • Deterministic Action Triggers (Approve, Reject, Draft Requisition)   │
 │  • Natural Language Query & Filter Bar (Teemo-Style Analyst)            │
 └───────────────────────────────────┬─────────────────────────────────────┘
                                     │
                 ┌───────────────────┴───────────────────┐
                 │                                       │
                 ▼                                       ▼
 ┌───────────────────────────────┐       ┌───────────────────────────────┐
 │     DETERMINISTIC ENGINE      │       │     AI ORCHESTRATION LAYER    │
 │  • Rama Stream Topologies     │       │  • Agent-o-Rama (Durable Run) │
 │  • PStates ($$employees, etc) │◄──────┤  • Model Context Protocol     │
 │  • ABAC / RBAC Permissioning  │       │  • Escapement Statecharts     │
 │  • Pathom 3 EQL Graph Engine  │       │  • Targeted Policy RAG        │
 └───────────────────────────────┘       └───────────────────────────────┘
```

---

## 3. Detailed Component Breakdown

### 3.1 The Deterministic Core (Rama Event Sourcing)
All business facts, state transitions, and analytical rollups are computed natively inside Red Planet Labs Rama:
- **Immutable Depots**: Depots (`*employee-depot`, `*headcount-depot`, `*org-unit-depot`) record append-only operational events with idempotency keys.
- **Real-Time PStates**: Aggregations (`$$unit-headcount-stats`, `$$unit-cost-stats`, `$$approval-sla`) are updated partition-locally in microseconds. Loaded costs (base salary $\times$ regional load factors $+$ currency conversions) are strictly computed by pure deterministic code—never by an LLM.
- **ABAC/RBAC Filtering**: Data is filtered before reaching any surface, ensuring viewers only see fields allowed by their organizational role.

### 3.2 The Visual Canvas (Replicant + Fulcro)
- **Spatial Anchors**: Users navigate organizations hierarchically, viewing reporting lines, vacant requisition slots, and departmental health metrics in place.
- **Zero Prompt Fatigue**: Routine tasks (filtering by department, inspecting salary bands, or approving pending steps) are accomplished in one click rather than typing repetitive prompts.
- **Visual Feedback for AI Actions**: When the AI performs actions or answers queries, it highlights, filters, or slots draft cards directly onto the visual canvas.

### 3.3 The Tool & Execution Standard: Model Context Protocol (MCP)
Rather than writing proprietary LLM glue code, `workforce` exposes its operational capabilities via standard **Model Context Protocol (MCP)** tools (`com.ozimos.workforce.org.tools.mcp`):
- **Read Tools**: `get-org-workforce-chart`, `search-org-workforce`, `get-unit-headcount-stats`, `get-approval-sla-latencies`.
- **Write / Mutation Tools**: `create-headcount-request!`, `approve-headcount-step!`, `reject-headcount-request!`, `edit-headcount-field!`.

**Why MCP Wins Over Ad-Hoc Tooling:**
- **Vendor Agnostic**: Works interchangeably with Claude Desktop, Cursor, in-house UI assistants, or autonomous background agents.
- **Security & Scope**: Every MCP call receives the authenticated user's `viewer-ctx`, guaranteeing that the LLM can never read or mutate data beyond the user's explicit permissions.

### 3.4 Durable Workflow & Agent Execution: Escapement & Agent-o-Rama
Enterprise headcount operations are **long-running distributed state machines** that can take days or weeks (e.g., requisition approvals, hiring freezes, cross-system syncing).

- **Escapement Statecharts**: Model deterministic, sequential multi-step approval chains (`:draft` $\rightarrow$ `:in-approval` $\rightarrow$ `:approved` $\rightarrow$ `:filled`), tracking SLA deadlines and escalation transitions.
- **Agent-o-Rama**: Red Planet Labs' native agent runtime built directly on Rama.
  - **Durable Memory**: Every LLM prompt, scratchpad thought, and tool execution is persisted into Rama PStates. If nodes reboot or fail, the agent resumes from the exact microsecond state without repeating external side effects.
  - **Native Human-in-the-Loop**: Agents can initiate backfill proposals, draft requisition criteria, pause execution, and wait indefinitely until a manager clicks "Approve" on the Canvas.
  - **Event-Driven Reactivity**: Instead of polling external APIs, agents wake up reactively when events hit Rama depots (e.g., an employee resignation event instantly triggers the backfill agent).

---

## 4. MCP vs. RAG: Dispelling the False Dichotomy

A common misconception is choosing between **MCP** and **RAG**. In a workforce intelligence platform, they serve distinct, complementary roles:

| Dimension | Model Context Protocol (MCP) | Retrieval-Augmented Generation (RAG) |
| :--- | :--- | :--- |
| **Primary Domain** | **Structured Live State & Actions** | **Unstructured Text & Knowledge** |
| **Data Handled** | Live PStates, org trees, budgets, headcounts, active approvals. | Employee handbooks, compensation philosophy PDFs, job leveling rubrics. |
| **Guarantees** | 100% deterministic, mathematically exact, real-time. | Semantic similarity, probabilistic, fuzzy match. |
| **Capabilities** | Reads live data **and executes state changes**. | Read-only context injection. |

### How They Converge in Practice
When an executive asks:  
> *"Draft a backfill requisition for an L5 SRE in our London office following our remote equipment stipend guidelines."*

1. **RAG Component**: Searches the company handbook for the unstructured policy text on remote equipment allowances in the UK.
2. **MCP / Rama Component**: Queries `$$employments` and `$$org-currency-settings` via MCP to retrieve the deterministic L5 engineering salary band and the active GBP $\rightarrow$ USD exchange rate.
3. **LLM Synthesis**: Combines the policy context with the exact financial data to produce an accurate draft.
4. **Action via MCP / Statechart**: Emits `create-headcount-request!` to Rama as a `:draft` card on the Org Chart Canvas for human sign-off.

---

## 5. Phased Implementation Roadmap

### Phase 1: In-Canvas Conversational Analyst (Immediate)
- Embed a `Cmd+K` natural language assistant into the frontend canvas.
- Connect the assistant to the existing MCP query tools (`mcp.clj`).
- Enable natural language filtering: queries like *"Show backend roles exceeding budget"* dynamically filter and highlight nodes on the active visual Org Chart.

### Phase 2: Autonomous Requisition Drafting & Auditing (Near Term)
- Utilize Escapement statecharts and MCP mutation tools to support AI-assisted drafting.
- Background anomaly auditing: scans `$$approval-sla` and `$$unit-cost-stats` to flag stagnant requisitions or misaligned salary bands directly on manager dashboards.

### Phase 3: Agent-o-Rama Distributed Multi-Agent Automation (Long Term)
- Deploy long-running background agents on the Rama cluster using Agent-o-Rama.
- Event-driven cross-system automation: auto-drafting backfills on termination events, orchestrating approvals across Slack and the Web UI, and triggering Greenhouse/Workday integrations upon final sign-off.

---

## 6. Conclusion

For workforce and headcount intelligence, the UI is not an optional afterthought—it is the foundational control center. By anchoring the platform on an immutable **Rama event-sourced core**, presenting a visual **Org Chart Canvas**, and orchestrating operations through **MCP and Agent-o-Rama**, `workforce` achieves the optimal balance of **mathematical precision, spatial clarity, and agentic automation**.
