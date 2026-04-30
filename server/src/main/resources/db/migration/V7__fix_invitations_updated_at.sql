-- V7 — Add missing updated_at to invitations table
ALTER TABLE invitations
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW();

CREATE TRIGGER update_invitations_updated_at
    BEFORE UPDATE ON invitations
    FOR EACH ROW EXECUTE FUNCTION update_updated_at();
