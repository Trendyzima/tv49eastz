-- TV 49 East federation persistence.
-- Protocol target: ActivityPub + WebFinger + NodeInfo discovery.
-- This is an interoperability layer, not an embedded Mastodon/Pixelfed implementation.

create table if not exists public.federated_instances (
  id uuid primary key default gen_random_uuid(),
  domain text not null unique check (domain = lower(domain)),
  software_name text,
  software_version text,
  nodeinfo_url text,
  protocol text not null default 'activitypub',
  status text not null default 'active' check (status in ('active','limited','blocked','error')),
  last_seen_at timestamptz,
  last_error text,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create table if not exists public.federated_actors (
  id uuid primary key default gen_random_uuid(),
  instance_id uuid references public.federated_instances(id) on delete set null,
  uri text not null unique,
  webfinger text,
  preferred_username text,
  display_name text,
  summary text,
  avatar_url text,
  header_url text,
  actor_type text not null default 'Person',
  inbox_url text,
  shared_inbox_url text,
  outbox_url text,
  followers_url text,
  following_url text,
  public_key_id text,
  public_key_pem text,
  discoverable boolean not null default true,
  locked boolean not null default false,
  suspended boolean not null default false,
  raw_actor jsonb not null default '{}'::jsonb,
  fetched_at timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
create index if not exists federated_actors_instance_idx on public.federated_actors(instance_id);
create index if not exists federated_actors_username_idx on public.federated_actors(preferred_username, display_name);

create table if not exists public.federated_objects (
  id uuid primary key default gen_random_uuid(),
  uri text not null unique,
  object_type text not null,
  actor_uri text,
  instance_id uuid references public.federated_instances(id) on delete set null,
  url text,
  content text,
  summary text,
  published_at timestamptz,
  updated_at timestamptz,
  sensitive boolean not null default false,
  in_reply_to_uri text,
  quote_uri text,
  language_code text,
  attachments jsonb not null default '[]'::jsonb,
  tags jsonb not null default '[]'::jsonb,
  raw_object jsonb not null default '{}'::jsonb,
  created_at timestamptz not null default now()
);
create index if not exists federated_objects_actor_idx on public.federated_objects(actor_uri, published_at desc);
create index if not exists federated_objects_instance_idx on public.federated_objects(instance_id, published_at desc);
create index if not exists federated_objects_reply_idx on public.federated_objects(in_reply_to_uri);

create table if not exists public.federated_activities (
  id uuid primary key default gen_random_uuid(),
  uri text not null unique,
  activity_type text not null,
  actor_uri text not null,
  object_uri text,
  target_uri text,
  instance_id uuid references public.federated_instances(id) on delete set null,
  raw_activity jsonb not null default '{}'::jsonb,
  received_at timestamptz not null default now(),
  processed_at timestamptz,
  processing_error text
);
create index if not exists federated_activities_actor_idx on public.federated_activities(actor_uri, received_at desc);
create index if not exists federated_activities_object_idx on public.federated_activities(object_uri, received_at desc);

create table if not exists public.federated_relationships (
  id uuid primary key default gen_random_uuid(),
  local_user_id uuid not null references public.profiles(id) on delete cascade,
  remote_actor_uri text not null,
  relationship text not null check (relationship in ('following','follower','blocked','muted')),
  state text not null default 'pending' check (state in ('pending','accepted','rejected','active','removed')),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique(local_user_id, remote_actor_uri, relationship)
);
create index if not exists federated_relationships_user_idx on public.federated_relationships(local_user_id, relationship, state);

create table if not exists public.federation_deliveries (
  id uuid primary key default gen_random_uuid(),
  activity_id uuid not null references public.federated_activities(id) on delete cascade,
  target_inbox text not null,
  instance_domain text not null,
  status text not null default 'pending' check (status in ('pending','in_flight','delivered','retry','dead')),
  attempt_count integer not null default 0 check (attempt_count >= 0),
  next_attempt_at timestamptz not null default now(),
  last_attempt_at timestamptz,
  locked_at timestamptz,
  last_status_code integer,
  last_error text,
  delivered_at timestamptz,
  created_at timestamptz not null default now(),
  unique(activity_id, target_inbox)
);
create index if not exists federation_deliveries_queue_idx on public.federation_deliveries(status, next_attempt_at);
create index if not exists federation_deliveries_domain_idx on public.federation_deliveries(instance_domain, status);

create table if not exists public.federation_domain_rules (
  domain text primary key check (domain = lower(domain)),
  action text not null check (action in ('allow','block','silence')),
  reason text not null default '',
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create table if not exists public.federation_webfinger_cache (
  resource text primary key,
  subject text,
  actor_uri text,
  instance_domain text,
  links jsonb not null default '[]'::jsonb,
  expires_at timestamptz,
  fetched_at timestamptz not null default now()
);
create index if not exists federation_webfinger_expiry_idx on public.federation_webfinger_cache(expires_at);

-- Deduplication and lookup indexes used by the delivery worker.
create unique index if not exists federated_objects_uri_lower_idx on public.federated_objects(lower(uri));
create unique index if not exists federated_activities_uri_lower_idx on public.federated_activities(lower(uri));

-- Federation data is never writable directly by the mobile client.
-- Service-role workers own ingestion/delivery. Authenticated users can read
-- normalized remote actors/objects through the API gateway.
alter table public.federated_instances enable row level security;
alter table public.federated_actors enable row level security;
alter table public.federated_objects enable row level security;
alter table public.federated_activities enable row level security;
alter table public.federated_relationships enable row level security;
alter table public.federation_deliveries enable row level security;
alter table public.federation_domain_rules enable row level security;
alter table public.federation_webfinger_cache enable row level security;

drop policy if exists federated_instances_read on public.federated_instances;
create policy federated_instances_read on public.federated_instances for select to authenticated using (true);
drop policy if exists federated_actors_read on public.federated_actors;
create policy federated_actors_read on public.federated_actors for select to authenticated using (true);
drop policy if exists federated_objects_read on public.federated_objects;
create policy federated_objects_read on public.federated_objects for select to authenticated using (true);
drop policy if exists federated_activities_read on public.federated_activities;
create policy federated_activities_read on public.federated_activities for select to authenticated using (true);
drop policy if exists federated_relationships_owner_read on public.federated_relationships;
create policy federated_relationships_owner_read on public.federated_relationships for select to authenticated using (local_user_id = auth.uid());
drop policy if exists federation_webfinger_cache_read on public.federation_webfinger_cache;
create policy federation_webfinger_cache_read on public.federation_webfinger_cache for select to authenticated using (true);

-- No client policy is granted for delivery queues or domain rules. The
-- federation service uses the Supabase service role and bypasses RLS.
