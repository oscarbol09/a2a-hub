-- Permanent attribution survives deletion of the optional live agent relationship.
ALTER TABLE tasks ADD COLUMN original_agent_id UUID;
ALTER TABLE tasks ADD COLUMN original_agent_name VARCHAR(255);

-- Keep V2 timestamp semantics: this backfill is a row modification and advances
-- updated_at. created_at, execution data, and lock_version are not rewritten.
UPDATE tasks t
SET original_agent_id = a.id, original_agent_name = a.name
FROM agents a
WHERE t.agent_id = a.id;

ALTER TABLE tasks ALTER COLUMN original_agent_id SET NOT NULL;
ALTER TABLE tasks ALTER COLUMN original_agent_name SET NOT NULL;
ALTER TABLE tasks ALTER COLUMN agent_id DROP NOT NULL;
ALTER TABLE tasks DROP CONSTRAINT tasks_agent_id_fkey;
ALTER TABLE tasks ADD CONSTRAINT tasks_agent_id_fkey
    FOREIGN KEY (agent_id) REFERENCES agents(id) ON DELETE SET NULL;

-- History now filters on original_agent_id. Existing agent_id indexes cannot
-- serve that predicate; retain them for live relationships and active-task checks.
CREATE INDEX idx_tasks_original_agent_keyset ON tasks (original_agent_id, id DESC);

-- FK detachment also advances updated_at through the unchanged V2 trigger.
