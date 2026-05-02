-- Link a pre-created agent row to its registration token so register()
-- can update the existing PENDING agent instead of inserting a duplicate.
ALTER TABLE agent_registration_tokens
    ADD COLUMN IF NOT EXISTS agent_id UUID REFERENCES agents(id) ON DELETE SET NULL;

CREATE INDEX IF NOT EXISTS idx_reg_tokens_agent_id ON agent_registration_tokens(agent_id);
