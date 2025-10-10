# Ticketing Gateway System

A lightweight, three-service setup for tracking IT tickets and notifying users.



## Components

### 1) Gateway Server
- Sits in front of the backend services.
- Routes client traffic to internal services (e.g., `/api/** → ticket-service`).
- Central place for cross-cutting concerns (CORS, auth, rate-limits, headers).
- Typical port: **8080** (configurable).

### 2) Ticket Service
- Core REST API and persistence layer for tickets and ticket history.
- Manages full ticket lifecycle: **SUBMITTED → APPROVED/REJECTED → IN_PROGRESS → RESOLVED → CLOSED → REOPENED**.
- Emits **JMS events** on important actions (created, assigned, rejected, resolved, auto-close triggers).
- Scheduled job(s) to auto-close stale items (e.g., untouched > 7 days) and resolve-then-close flows.
- Typical port: **8081** (configurable).

### 3) Notification Service
- Listens to the Ticket Service’s JMS events.
- Sends emails to requesters, assignees, and/or managers.
- On resolution, generates and attaches a simple **PDF summary** to the email.
- Can run with an embedded broker in dev, or connect to a shared broker in all environments.


## Tech Specs

- **Language/Runtime:** Java 21  
- **Framework:** Spring Boot 3.x  
- **Database:** MySQL 8 (tickets + history)  
- **Messaging:** ActiveMQ (classic 5.x in dev; Artemis or managed broker recommended in prod)  
- **Build:** Maven  
- **Email:** JavaMailSender (SMTP)  
- **Ports (defaults):** Gateway **8080**, Ticket **8081**, Broker **61616**  
- **JMS Destinations (queues):**
  - `ticket.created`
  - `ticket.assigned`
  - `ticket.rejected`
  - `ticket.resolved`
  - `ticket.autoclose` (compat/command)
  - `ticket.autoclose.stale` (>7d untouched)
  - `ticket.autoclose.afterResolved` (5d after resolved)


## What the App Does

- Provides a simple, reliable way to **submit and manage IT tickets**.
- Tracks every state change with a **history trail** for auditability.
- Sends **notifications** at key points (created, assigned, rejected, resolved, auto-closed).
- Encourages closure hygiene with **auto-close rules**:
  - **Stale** (no activity for 7 days) → auto-close + email.
  - **Resolved but not closed (5 days)** → reminder/auto-close + email.
- Produces a **PDF resolution summary** and attaches it to the resolution email.



## Ticket Tracking (Overview)

- **Create & View:** capture title/description, requester, assignee; list active/history.
- **Update & Assign:** edit fields and assign ownership; all edits update “last touched”.
- **Status Changes:** enforce logical transitions; auto-stamp timestamps (e.g., `resolvedAt`).
- **History:** each mutation adds a history entry (who/when/what).
- **Auto-Close Policies:**
  - **Stale > 7 days:** system closes and notifies requester.
  - **Resolved + 5 days:** system closes if not manually closed; notifies requester.


## Notifications (Overview)

- **Created:** email to IT/Admin (manager) with ticket details.
- **Assigned:** email to the assignee (fallback to manager if unknown).
- **Rejected:** email to requester with the rejection reason.
- **Resolved:** email to requester, including optional **PDF resolution summary**.
- **Auto-Closed:** email to requester for both stale and post-resolved cases.

## Notes

- For development simplicity, you can run an embedded ActiveMQ broker; in production, use a **managed** broker.
- If you previously relied on per-message JMS delivery delays, those are avoided for compatibility; periodic sweeps/listeners handle the “resolved + 5 days” flow.
- Configure SMTP, database, and broker endpoints via standard Spring properties per service.

