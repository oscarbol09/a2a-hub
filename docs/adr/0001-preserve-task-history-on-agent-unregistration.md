# Preserve task history on agent unregistration

## Context

Issue #30: unregister previously deleted all tasks before deleting their agent,
bypassing the database's restrictive task foreign key and destroying both history
and records needed by in-flight execution. The requested behavior is HTTP 409
when SUBMITTED or WORKING tasks exist, and retention of historical task records.

## Decision

Keep physical agent deletion. V6 makes `tasks.agent_id` optional with
`ON DELETE SET NULL`. Non-null `original_agent_id` and `original_agent_name`
retain attribution independently of the live relationship. Task creation copies
them from the locked agent; Hibernate does not update them afterward. Existing
rows are backfilled with the currently associated agent's ID/name. Previous names
that were overwritten before migration cannot be recovered.

Task DTOs and both history queries read this permanent attribution. Public fields,
cursor ordering, list limits, statuses, and frontend contracts remain unchanged.
Re-registering a URL creates a new identity without inheriting old tasks.

Task creation and unregister acquire the same PostgreSQL pessimistic agent-row
lock inside their existing short transactions. Creation revalidates existence and
OFFLINE status under that lock. Unregister checks SUBMITTED/WORKING after locking,
before any cleanup, and never deletes tasks. If creation commits first, unregister
sees the active task and rejects it. If unregister commits first, admission finds
no agent and rejects before downstream dispatch. No database transaction is held
across network I/O. Existing propagation and isolation settings are retained.

This coordination assumes the repository's PostgreSQL default, Read Committed.
An externally configured higher isolation level needs separate validation; do not
assume that a query after waiting for a lock sees newly committed child rows under
Repeatable Read. Direct administrative SQL is outside the service admission guard.

## Index choice

Add only `(original_agent_id, id DESC)` for history equality/cursor queries.
Existing indexes begin with `agent_id` and cannot support the new history filter.
The new index also supports the full-history equality predicate, with the existing
created-at sort performed separately. Keep all existing indexes, especially the
live `(agent_id, state)` index for active-task checks. No covering-index redesign,
index replacement, or autovacuum changes are included.

## Timestamps and transactions

V2's `fn_set_updated_at` and both timestamp triggers are unchanged. `updated_at`
continues to mean last row modification, not exclusively execution completion:

- V6 attribution backfill changes each existing task and advances `updated_at`.
- FK detachment changes `agent_id` to null and advances `updated_at` again.
- Execution updates still advance it; SQL updates leaving row values unchanged
  do not advance it.
- Backfill and detachment preserve `created_at`, execution data, and
  `lock_version`; subsequent ORM execution updates retain optimistic versioning.

Unregister's skill cleanup, agent deletion, and task detachment share one database
transaction. Failure rolls them all back. V6 is a transactional Flyway migration;
failure also rolls back its backfill, timestamp changes, DDL, and index creation.

Missing-agent unregister remains HTTP 204 with the existing UNREGISTERED event.
Notification timing stays unchanged, including the pre-existing risk that a
notification can escape a later database rollback. Moving publication after commit
is separate work, not part of this fix. Active-task rejection occurs before any
notification or mutation.

## Alternatives and operational limits

Soft deletion would retain the parent but require lifecycle handling in discovery,
health probes, task admission, statistics, and URL registration. Nullable links plus
permanent attribution keep this change focused on task retention and admission.

Drain work and stop the old Hub before deploying V6 and the new application.
Backfilling history and validating the FK/index can take locks on large tables.
Old binaries cannot populate the required attribution columns or safely read
detached history. Use a forward correction or coordinated database restore and
application rollback; simply restarting the old binary against V6 is unsupported.

Regression coverage uses real PostgreSQL for history queries, timestamp semantics,
unchanged data on rejection, database and migration rollback, and both lock
orderings. HTTP 409 and detached DTO compatibility are checked separately through
the existing MockMvc and unit-test patterns.
