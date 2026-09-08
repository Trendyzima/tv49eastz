-- PayPal webhook audit/idempotency ledger.
-- The event_id is the primary replay barrier: PayPal can retry the same
-- notification, so processing must be safe to repeat.
create table if not exists public.paypal_webhook_events (
  event_id text primary key,
  event_type text not null,
  paypal_order_id text,
  paypal_capture_id text,
  transmission_id text,
  verified boolean not null default false,
  processed boolean not null default false,
  processing_error text,
  payload jsonb not null,
  received_at timestamptz not null default now(),
  processed_at timestamptz
);

create index if not exists paypal_webhook_events_order_idx
  on public.paypal_webhook_events(paypal_order_id, received_at desc);

create index if not exists paypal_webhook_events_received_idx
  on public.paypal_webhook_events(received_at desc);

alter table public.paypal_webhook_events enable row level security;

-- No client policy: webhook records are server-only. The Cloudflare Worker
-- uses the Supabase secret key and therefore bypasses RLS.

-- Allow the Worker to finalize a pending wallet top-up exactly once.
create or replace function public.finalize_paypal_topup(
  p_order_id text,
  p_capture_id text
)
returns public.wallet_transactions
language plpgsql
security definer
set search_path=public
as $$
declare
  tx public.wallet_transactions;
  result public.wallet_transactions;
begin
  select * into tx
    from public.wallet_transactions
   where provider='paypal'
     and provider_order_id=p_order_id
   for update;

  if not found then raise exception 'wallet transaction not found'; end if;
  if tx.status='completed' then return tx; end if;
  if tx.status <> 'pending' then raise exception 'wallet transaction is not pending'; end if;

  insert into public.wallets(user_id,balance_cents,currency)
  values(tx.user_id,tx.amount_cents,tx.currency)
  on conflict(user_id) do update
    set balance_cents=public.wallets.balance_cents+excluded.balance_cents,
        updated_at=now();

  update public.wallet_transactions
     set status='completed',
         provider_capture_id=p_capture_id,
         completed_at=now()
   where id=tx.id
   returning * into result;

  update public.paypal_orders
     set status='captured', captured_at=now()
   where paypal_order_id=p_order_id;

  return result;
end;
$$;

revoke all on function public.finalize_paypal_topup(text,text) from public;
