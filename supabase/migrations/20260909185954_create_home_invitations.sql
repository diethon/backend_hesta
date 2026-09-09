CREATE TABLE home_invitations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    home_id UUID NOT NULL REFERENCES homes(id) ON DELETE CASCADE,
    inviter_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    invite_code VARCHAR(10) UNIQUE,
    invite_token VARCHAR(100) UNIQUE,
    target_email VARCHAR(255),
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_home_invitations_home_id ON home_invitations(home_id);
CREATE INDEX idx_home_invitations_invite_code ON home_invitations(invite_code);
CREATE INDEX idx_home_invitations_invite_token ON home_invitations(invite_token);
