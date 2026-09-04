-- Realtime delivery for native notifications, DMs, poll votes and community membership.
do $$
declare t text;
begin
  foreach t in array array['notifications','messages','poll_votes','community_members'] loop
    if not exists (select 1 from pg_publication_tables where pubname='supabase_realtime' and schemaname='public' and tablename=t) then
      execute format('alter publication supabase_realtime add table public.%I', t);
    end if;
  end loop;
end $$;

-- Ensure the privileged finalizer can be called by the server-side Edge Function only.
revoke all on function public.finalize_paypal_topup(text,text) from public;
revoke all on function public.finalize_paypal_topup(text,text) from anon;
revoke all on function public.finalize_paypal_topup(text,text) from authenticated;
grant execute on function public.finalize_paypal_topup(text,text) to service_role;

-- Prevent duplicate wallet transaction rows for a single PayPal order even under retries.
create unique index if not exists paypal_wallet_tx_order_unique
  on public.wallet_transactions(provider_order_id)
  where provider = 'paypal' and provider_order_id is not null;
