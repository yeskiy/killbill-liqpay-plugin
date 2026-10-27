-- Copyright 2025-2026 Yehor Brodskyi
-- Licensed under the Apache License, Version 2.0 (http://www.apache.org/licenses/LICENSE-2.0).
-- PostgreSQL DDL of the killbill-liqpay plugin. Every statement can run again.

create table if not exists liqpay_transactions (
  record_id bigserial primary key,
  kb_account_id char(36) not null,
  kb_payment_id char(36) not null,
  kb_transaction_id char(36) not null,
  kb_payment_method_id char(36) not null,
  kb_tenant_id char(36) not null,
  transaction_type varchar(32) not null,
  liqpay_action varchar(32) not null,
  order_id varchar(255) not null,
  original_order_id varchar(255),
  amount numeric(15,2) not null,
  currency char(3) not null,
  plugin_status varchar(32) not null,
  liqpay_status varchar(64),
  liqpay_payment_id varchar(64),
  liqpay_end_date bigint,
  err_code varchar(100),
  err_description varchar(1000),
  additional_data text,
  expires_at timestamp,
  created_date timestamp not null,
  updated_date timestamp not null,
  unique (kb_transaction_id, kb_tenant_id),
  unique (order_id, kb_tenant_id)
);
create index if not exists liqpay_transactions_payment on liqpay_transactions (kb_payment_id, kb_tenant_id);
create index if not exists liqpay_transactions_pending on liqpay_transactions (plugin_status, kb_tenant_id);

create table if not exists liqpay_payment_methods (
  record_id bigserial primary key,
  kb_account_id char(36) not null,
  kb_payment_method_id char(36) not null,
  kb_tenant_id char(36) not null,
  mode varchar(16) not null,
  card_token_enc bytea,
  card_token_key_id varchar(16),
  verified boolean not null default false,
  card_mask varchar(32),
  card_type varchar(32),
  is_deleted boolean not null default false,
  created_date timestamp not null,
  updated_date timestamp not null,
  unique (kb_payment_method_id, kb_tenant_id)
);

create table if not exists liqpay_hpp_requests (
  record_id bigserial primary key,
  kb_account_id char(36) not null,
  kb_payment_method_id char(36) not null,
  kb_tenant_id char(36) not null,
  order_id varchar(255) not null,
  amount numeric(15,2) not null,
  currency char(3) not null,
  status varchar(32) not null,
  liqpay_end_date bigint,
  created_date timestamp not null,
  updated_date timestamp not null,
  unique (order_id, kb_tenant_id)
);
