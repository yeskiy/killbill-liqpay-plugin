/*
 * Copyright 2024 Sailkit.io
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

-- LiqPay Payment Plugin Database Schema
-- These tables store LiqPay-specific data that cannot be stored in standard KillBill tables

-- 1. Store card tokens linked to KillBill payment methods
-- Used for recurring/token-based payments
CREATE TABLE liqpay_payment_methods (
    record_id SERIAL PRIMARY KEY,

    -- KillBill references
    kb_account_id CHAR(36) NOT NULL,
    kb_payment_method_id CHAR(36) NOT NULL,
    kb_tenant_id CHAR(36) NOT NULL,

    -- LiqPay token data
    liqpay_card_token VARCHAR(255),
    card_mask VARCHAR(20),              -- e.g., "473119******4634"
    card_type VARCHAR(20),              -- visa, mastercard
    card_bank VARCHAR(100),
    card_country VARCHAR(10),           -- ISO 3166-1 numeric

    -- Metadata
    is_default BOOLEAN DEFAULT FALSE,
    is_deleted BOOLEAN DEFAULT FALSE,
    additional_data TEXT,               -- JSON for extra fields

    created_date TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_date TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    UNIQUE (kb_payment_method_id, kb_tenant_id)
);

CREATE INDEX idx_liqpay_pm_account ON liqpay_payment_methods(kb_account_id, kb_tenant_id);
CREATE INDEX idx_liqpay_pm_token ON liqpay_payment_methods(liqpay_card_token);


-- 2. Store LiqPay API responses for audit/debugging and to support getPaymentInfo() calls
CREATE TABLE liqpay_responses (
    record_id SERIAL PRIMARY KEY,

    -- KillBill references
    kb_account_id CHAR(36),
    kb_payment_id CHAR(36),
    kb_transaction_id CHAR(36),
    kb_payment_method_id CHAR(36),
    kb_tenant_id CHAR(36) NOT NULL,

    -- LiqPay identifiers
    liqpay_order_id VARCHAR(255) NOT NULL,
    liqpay_payment_id VARCHAR(50),
    liqpay_transaction_id VARCHAR(50),

    -- Transaction details
    transaction_type VARCHAR(32),       -- AUTHORIZE, CAPTURE, PURCHASE, VOID, REFUND, CREDIT
    action VARCHAR(50),                 -- pay, hold, refund, etc.
    status VARCHAR(50) NOT NULL,        -- success, error, hold_wait, etc.
    amount DECIMAL(15,5),
    currency CHAR(3),

    -- Error info
    err_code VARCHAR(100),
    err_description TEXT,

    -- Full response for audit
    raw_response TEXT,                  -- Full JSON response

    -- Timing
    api_call_date TIMESTAMP,
    created_date TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_liqpay_resp_payment ON liqpay_responses(kb_payment_id);
CREATE INDEX idx_liqpay_resp_transaction ON liqpay_responses(kb_transaction_id);
CREATE INDEX idx_liqpay_resp_order ON liqpay_responses(liqpay_order_id);
CREATE INDEX idx_liqpay_resp_tenant ON liqpay_responses(kb_tenant_id);


-- 3. Track pending transactions awaiting callback (for hosted page flow)
CREATE TABLE liqpay_pending_transactions (
    record_id SERIAL PRIMARY KEY,

    -- KillBill references
    kb_account_id CHAR(36) NOT NULL,
    kb_payment_id CHAR(36),
    kb_transaction_id CHAR(36) NOT NULL,
    kb_payment_method_id CHAR(36),
    kb_tenant_id CHAR(36) NOT NULL,

    -- LiqPay order ID (our transaction ID sent to LiqPay)
    order_id VARCHAR(255) NOT NULL,

    -- Transaction details
    transaction_type VARCHAR(32),       -- AUTHORIZE, PURCHASE
    amount DECIMAL(15,5),
    currency CHAR(3),

    -- Status tracking
    status VARCHAR(32) DEFAULT 'PENDING', -- PENDING, COMPLETED, FAILED, EXPIRED

    created_date TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_date TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    UNIQUE (order_id, kb_tenant_id)
);

CREATE INDEX idx_liqpay_pending_tx ON liqpay_pending_transactions(kb_transaction_id, kb_tenant_id);
CREATE INDEX idx_liqpay_pending_order ON liqpay_pending_transactions(order_id);
CREATE INDEX idx_liqpay_pending_status ON liqpay_pending_transactions(status);


-- 4. Track hosted payment page (HPP) requests for buildFormDescriptor flow
-- Stores session data for checkout redirect/embed flows
CREATE TABLE liqpay_hpp_requests (
    record_id SERIAL PRIMARY KEY,

    -- KillBill references
    kb_account_id CHAR(36) NOT NULL,
    kb_payment_method_id CHAR(36),
    kb_tenant_id CHAR(36) NOT NULL,

    -- Session identifiers
    session_id VARCHAR(255) NOT NULL,     -- Internal session ID (UUID)
    order_id VARCHAR(255) NOT NULL,       -- LiqPay order ID (hpp-{uuid})

    -- Request configuration
    mode VARCHAR(20) DEFAULT 'redirect',  -- redirect, embed, popup
    is_verification BOOLEAN DEFAULT FALSE, -- true = card verification (auto-unhold)
    amount DECIMAL(15,5),
    currency CHAR(3),

    -- Additional data (JSON) - stores data, signature, urls
    additional_data TEXT,

    -- Status tracking
    status VARCHAR(32) DEFAULT 'CREATED', -- CREATED, COMPLETED, FAILED, EXPIRED

    created_date TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_date TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    UNIQUE (session_id, kb_tenant_id),
    UNIQUE (order_id, kb_tenant_id)
);

CREATE INDEX idx_liqpay_hpp_session ON liqpay_hpp_requests(session_id);
CREATE INDEX idx_liqpay_hpp_order ON liqpay_hpp_requests(order_id);
CREATE INDEX idx_liqpay_hpp_account ON liqpay_hpp_requests(kb_account_id, kb_tenant_id);
CREATE INDEX idx_liqpay_hpp_status ON liqpay_hpp_requests(status);
