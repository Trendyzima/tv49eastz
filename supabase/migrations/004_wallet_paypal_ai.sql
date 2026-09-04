-- TV 49 East wallet/payment + AI foundation.
-- PayPal secrets/tokens MUST stay server-side. Android never receives a PayPal secret.
create table if not exists public.wallets (
  user_id uuid primary key references public.profiles(id) on delete cascade,
  balance_cents bigint not null default 0 check(balance_cents >= 0),
  currency text not null default 'USD',
  updated_at timestamptz not null default now()
);
create table if not exists public.wallet_transactions (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references public.profiles(id) on delete cascade,
  kind text not null check(kind in ('topup','tip','subscription','purchase','refund','adjustment')),
  amount_cents bigint not null check(amount_cents > 0),
  currency text not null default 'USD',
  direction text not null check(direction in ('credit','debit')),
  status text not null default 'pending' check(status in ('pending','completed','failed','refunded')),
  provider text,
  provider_order_id text,
  provider_capture_id text,
  description text not null default '',
  created_at timestamptz not null default now(),
  completed_at timestamptz
);
create index if not exists wallet_transactions_user_idx on public.wallet_transactions(user_id,created_at desc);
create unique index if not exists wallet_paypal_order_idx on public.wallet_transactions(provider,provider_order_id) where provider_order_id is not null;
create table if not exists public.paypal_orders (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references public.profiles(id) on delete cascade,
  wallet_transaction_id uuid not null references public.wallet_transactions(id) on delete cascade,
  paypal_order_id text not null unique,
  amount_cents bigint not null check(amount_cents > 0),
  currency text not null default 'USD',
  status text not null default 'created' check(status in ('created','approved','captured','failed','cancelled')),
  approval_url text,
  created_at timestamptz not null default now(),
  captured_at timestamptz
);
create table if not exists public.ai_usage (
  id uuid primary key default gen_random_uuid(),
  user_id uuid references public.profiles(id) on delete set null,
  feature text not null,
  model text,
  input_tokens bigint not null default 0,
  output_tokens bigint not null default 0,
  estimated_cost_micros bigint not null default 0,
  created_at timestamptz not null default now()
);
create index if not exists ai_usage_user_idx on public.ai_usage(user_id,created_at desc);

alter table public.wallets enable row level security;
alter table public.wallet_transactions enable row level security;
alter table public.paypal_orders enable row level security;
alter table public.ai_usage enable row level security;
create policy wallet_read_own on public.wallets for select to authenticated using(user_id=auth.uid());
create policy wallet_tx_read_own on public.wallet_transactions for select to authenticated using(user_id=auth.uid());
create policy paypal_orders_read_own on public.paypal_orders for select to authenticated using(user_id=auth.uid());
create policy ai_usage_read_own on public.ai_usage for select to authenticated using(user_id=auth.uid());

create or replace function public.ensure_wallet(p_user_id uuid)
returns public.wallets language plpgsql security definer set search_path=public
as $$
declare w public.wallets;
begin
  if auth.uid() is null or auth.uid() <> p_user_id then raise exception 'forbidden'; end if;
  insert into public.wallets(user_id) values(p_user_id) on conflict(user_id) do nothing;
  select * into w from public.wallets where user_id=p_user_id;
  return w;
end;
$$;
revoke all on function public.ensure_wallet(uuid) from public;
grant execute on function public.ensure_wallet(uuid) to authenticated;
