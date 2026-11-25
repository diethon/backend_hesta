-- Migration: Create installation_contracts domain and add unit_price/warranty_months to device_catalogs
-- Supports HESTA end-to-end commercial contract, deposit verification, and turnkey provisioning flow.

ALTER TABLE device_catalogs
    ADD COLUMN IF NOT EXISTS unit_price NUMERIC(14, 2) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS warranty_months INTEGER NOT NULL DEFAULT 12;

-- Seed sensible reference prices (VND) for existing catalog items that still have 0 price
UPDATE device_catalogs
SET unit_price = CASE UPPER(device_type)
        WHEN 'LIGHT' THEN 420000
        WHEN 'LED_RGB' THEN 550000
        WHEN 'SWITCH' THEN 490000
        WHEN 'SMART_PLUG' THEN 320000
        WHEN 'PLUG' THEN 320000
        WHEN 'FAN' THEN 950000
        WHEN 'AIR_CONDITIONER' THEN 690000
        WHEN 'AC' THEN 690000
        WHEN 'IR_REMOTE' THEN 590000
        WHEN 'CURTAIN' THEN 1850000
        WHEN 'DOOR' THEN 2450000
        WHEN 'SMART_LOCK' THEN 2890000
        WHEN 'LOCK' THEN 2890000
        WHEN 'GATE' THEN 2650000
        WHEN 'ROLLING_DOOR' THEN 2350000
        WHEN 'CAMERA' THEN 1790000
        WHEN 'AI_CAMERA' THEN 2290000
        WHEN 'WATER_PUMP' THEN 780000
        WHEN 'PUMP' THEN 780000
        WHEN 'TV' THEN 590000
        WHEN 'TEMP_SENSOR' THEN 350000
        WHEN 'TEMPERATURE_SENSOR' THEN 350000
        WHEN 'GAS_SENSOR' THEN 480000
        WHEN 'SMOKE_SENSOR' THEN 450000
        WHEN 'FLAME_SENSOR' THEN 420000
        WHEN 'MOTION_SENSOR' THEN 390000
        WHEN 'LIGHT_SENSOR' THEN 340000
        ELSE 450000
    END,
    warranty_months = CASE UPPER(device_type)
        WHEN 'CAMERA' THEN 24
        WHEN 'AI_CAMERA' THEN 24
        WHEN 'SMART_LOCK' THEN 24
        WHEN 'LOCK' THEN 24
        WHEN 'GATE' THEN 24
        WHEN 'ROLLING_DOOR' THEN 24
        WHEN 'CURTAIN' THEN 24
        ELSE 12
    END
WHERE unit_price = 0;

CREATE TABLE IF NOT EXISTS installation_contracts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code VARCHAR(30) NOT NULL UNIQUE,
    claim_token VARCHAR(64) UNIQUE,
    claim_token_expires_at TIMESTAMPTZ,
    user_id UUID REFERENCES users(id) ON DELETE SET NULL,
    home_id UUID REFERENCES homes(id) ON DELETE SET NULL,

    -- Customer & project details
    customer_name VARCHAR(150) NOT NULL,
    customer_phone VARCHAR(30) NOT NULL,
    customer_email VARCHAR(150) NOT NULL,
    install_address TEXT NOT NULL,
    home_name VARCHAR(150),
    home_type VARCHAR(30) NOT NULL DEFAULT 'APARTMENT',
    room_count INTEGER DEFAULT 3,
    package_code VARCHAR(50),
    customer_note TEXT,

    -- Status & versioning
    status VARCHAR(30) NOT NULL DEFAULT 'SUBMITTED',
    contract_version INTEGER NOT NULL DEFAULT 1,
    template_code VARCHAR(50) NOT NULL DEFAULT 'STANDARD_HOME',

    -- Financials (VND)
    subtotal_amount NUMERIC(14, 2) NOT NULL DEFAULT 0,
    installation_fee NUMERIC(14, 2) NOT NULL DEFAULT 0,
    discount_amount NUMERIC(14, 2) NOT NULL DEFAULT 0,
    total_amount NUMERIC(14, 2) NOT NULL DEFAULT 0,
    deposit_percent INTEGER NOT NULL DEFAULT 30,
    deposit_amount NUMERIC(14, 2) NOT NULL DEFAULT 0,
    remaining_amount NUMERIC(14, 2) NOT NULL DEFAULT 0,

    -- Provider & Bank Transfer Snapshot (locked upon signing)
    provider_company_name VARCHAR(200) NOT NULL DEFAULT 'CÔNG TY TNHH CÔNG NGHỆ NHÀ THÔNG MINH HESTA',
    provider_tax_code VARCHAR(50) NOT NULL DEFAULT '0318202688',
    provider_address TEXT NOT NULL DEFAULT 'Lô E2a-7, Đường D1, Khu Công nghệ cao, TP. Thủ Đức, TP. Hồ Chí Minh',
    provider_representative VARCHAR(150) NOT NULL DEFAULT 'Bộ phận Triển khai & Quản trị Hệ thống HESTA',
    provider_hotline VARCHAR(30) NOT NULL DEFAULT '1900 2026',
    provider_email VARCHAR(150) NOT NULL DEFAULT 'contracts@hesta.homes',
    bank_name VARCHAR(200) NOT NULL DEFAULT 'TMCP Ngoại thương Việt Nam (Vietcombank) - Chi nhánh Thủ Đức',
    bank_account_number VARCHAR(50) NOT NULL DEFAULT '102688882026',
    bank_account_holder VARCHAR(150) NOT NULL DEFAULT 'CONG TY TNHH CONG NGHE HESTA',
    deposit_transfer_note VARCHAR(150),

    -- Deposit verification
    deposit_proof_url TEXT,
    deposit_reference_code VARCHAR(100),
    deposit_note TEXT,
    deposit_submitted_at TIMESTAMPTZ,
    deposit_verified_at TIMESTAMPTZ,
    deposit_verified_by UUID REFERENCES users(id) ON DELETE SET NULL,
    deposit_verified_amount NUMERIC(14, 2),

    -- Installation Schedule
    preferred_install_date TIMESTAMPTZ,
    scheduled_install_start TIMESTAMPTZ,
    scheduled_install_end TIMESTAMPTZ,

    -- Contract terms & E-Signatures
    terms_title VARCHAR(255) NOT NULL DEFAULT 'HỢP ĐỒNG CUNG CẤP VÀ LẮP ĐẶT HỆ THỐNG NHÀ THÔNG MINH HESTA',
    terms_content TEXT,
    special_terms TEXT,
    admin_note TEXT,
    cancel_reason TEXT,
    user_signature_name VARCHAR(150),
    user_signature_data TEXT,
    user_signed_at TIMESTAMPTZ,
    user_signed_ip VARCHAR(64),
    admin_signed_by UUID REFERENCES users(id) ON DELETE SET NULL,
    admin_signed_name VARCHAR(150),
    admin_signed_at TIMESTAMPTZ,

    -- Handover
    handover_mode VARCHAR(30),
    handed_over_at TIMESTAMPTZ,
    handed_over_by UUID REFERENCES users(id) ON DELETE SET NULL,
    handover_note TEXT,

    -- Optimistic lock & timestamps
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_installation_contracts_status ON installation_contracts(status);
CREATE INDEX IF NOT EXISTS idx_installation_contracts_user_id ON installation_contracts(user_id);
CREATE INDEX IF NOT EXISTS idx_installation_contracts_email ON installation_contracts(LOWER(customer_email));
CREATE INDEX IF NOT EXISTS idx_installation_contracts_home_id ON installation_contracts(home_id);
CREATE INDEX IF NOT EXISTS idx_installation_contracts_created_at ON installation_contracts(created_at DESC);

CREATE TABLE IF NOT EXISTS installation_contract_items (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    contract_id UUID NOT NULL REFERENCES installation_contracts(id) ON DELETE CASCADE,
    catalog_id UUID REFERENCES device_catalogs(id) ON DELETE SET NULL,
    device_name VARCHAR(150) NOT NULL,
    model_code VARCHAR(100),
    device_type VARCHAR(50),
    room_label VARCHAR(100),
    quantity INTEGER NOT NULL DEFAULT 1,
    unit_price NUMERIC(14, 2) NOT NULL DEFAULT 0,
    line_total NUMERIC(14, 2) NOT NULL DEFAULT 0,
    warranty_months INTEGER NOT NULL DEFAULT 12,
    sort_order INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_installation_contract_items_contract_id ON installation_contract_items(contract_id);

CREATE TABLE IF NOT EXISTS installation_contract_devices (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    contract_id UUID NOT NULL REFERENCES installation_contracts(id) ON DELETE CASCADE,
    contract_item_id UUID REFERENCES installation_contract_items(id) ON DELETE SET NULL,
    device_id UUID NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    room_id UUID REFERENCES rooms(id) ON DELETE SET NULL,
    device_name VARCHAR(150) NOT NULL,
    serial_number VARCHAR(100),
    room_name VARCHAR(100),
    warranty_months INTEGER NOT NULL DEFAULT 12,
    warranty_expires_at TIMESTAMPTZ,
    provisioned_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_installation_contract_devices_contract_id ON installation_contract_devices(contract_id);
CREATE INDEX IF NOT EXISTS idx_installation_contract_devices_device_id ON installation_contract_devices(device_id);

CREATE TABLE IF NOT EXISTS installation_contract_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    contract_id UUID NOT NULL REFERENCES installation_contracts(id) ON DELETE CASCADE,
    actor_id UUID REFERENCES users(id) ON DELETE SET NULL,
    actor_name VARCHAR(150),
    actor_role VARCHAR(20) NOT NULL,
    event_type VARCHAR(30) NOT NULL,
    from_status VARCHAR(30),
    to_status VARCHAR(30),
    message TEXT,
    metadata JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_installation_contract_events_contract_id ON installation_contract_events(contract_id, created_at ASC);
