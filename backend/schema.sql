-- YShott backend foundation. Apply once to a new Supabase database.
-- This file is a reviewed bootstrap draft, not a deployed migration.
begin;
create schema if not exists extensions;
create extension if not exists pgcrypto with schema extensions;
create schema if not exists yshott_private;
revoke all on schema yshott_private from public, anon;
grant usage on schema yshott_private to authenticated;
alter default privileges in schema yshott_private revoke execute on functions from public;
alter default privileges in schema public revoke execute on functions from public;

create table public.yshott_households (
  id uuid primary key default gen_random_uuid(),
  name text not null check (length(trim(name)) between 1 and 80),
  created_by uuid not null references auth.users(id),
  created_at timestamptz not null default now()
);
create table public.yshott_members (
  household_id uuid not null references public.yshott_households on delete cascade,
  user_id uuid not null references auth.users(id) on delete cascade,
  display_name text not null check (length(trim(display_name)) between 1 and 80),
  role text not null check (role in ('admin','adult')),
  status text not null check (status in ('pending','active','rejected','revoked')),
  requested_at timestamptz not null default now(),
  approved_by uuid references auth.users(id),
  primary key (household_id,user_id)
);
create index yshott_members_user_idx on public.yshott_members(user_id,status,household_id);
create table yshott_private.family_codes (
  household_id uuid primary key references public.yshott_households on delete cascade,
  code_hash bytea not null unique,
  expires_at timestamptz not null,
  created_at timestamptz not null default now()
);
create table yshott_private.join_attempts (
  user_id uuid not null references auth.users(id) on delete cascade,
  attempted_at timestamptz not null default now()
);
create index join_attempts_user_time_idx on yshott_private.join_attempts(user_id,attempted_at);
create table public.yshott_state (
  household_id uuid primary key references public.yshott_households on delete cascade,
  revision bigint not null default 0 check (revision >= 0),
  payload jsonb not null default '{"people":[],"events":[],"tasks":[],"groceries":[],"recipes":[],"meals":[],"rewards":[],"rewardRequests":[],"messages":[],"proposals":[],"residence":[]}',
  updated_by uuid references auth.users(id),
  updated_at timestamptz not null default now(),
  check (jsonb_typeof(payload) = 'object' and octet_length(payload::text) <= 1000000)
);
create table public.yshott_coparent_links (
  id uuid primary key default gen_random_uuid(),
  source_household uuid not null references public.yshott_households on delete cascade,
  target_household uuid not null references public.yshott_households on delete cascade,
  requested_by uuid not null references auth.users(id),
  status text not null default 'pending' check (status in ('pending','active','rejected','revoked')),
  decided_by uuid references auth.users(id),
  created_at timestamptz not null default now(),
  check (source_household <> target_household)
);
create unique index coparent_live_pair_idx on public.yshott_coparent_links
  (least(source_household,target_household),greatest(source_household,target_household))
  where status in ('pending','active');
create index coparent_target_idx on public.yshott_coparent_links(target_household,status);
create table public.yshott_calendar_events (
  id uuid primary key default gen_random_uuid(),
  household_id uuid not null references public.yshott_households on delete cascade,
  owner_id uuid not null references auth.users(id),
  starts_at timestamptz not null,
  ends_at timestamptz not null,
  title text not null check (length(trim(title)) between 1 and 200),
  location text not null default '' check (length(location) <= 1000),
  notes text not null default '' check (length(notes) <= 10000),
  check (ends_at > starts_at)
);
create index calendar_household_time_idx on public.yshott_calendar_events(household_id,starts_at);
create table public.yshott_calendar_grants (
  event_id uuid not null references public.yshott_calendar_events on delete cascade,
  recipient_household uuid not null references public.yshott_households on delete cascade,
  granted_by uuid not null references auth.users(id),
  created_at timestamptz not null default now(),
  primary key (event_id,recipient_household)
);

alter table public.yshott_households enable row level security;
alter table public.yshott_members enable row level security;
alter table public.yshott_state enable row level security;
alter table public.yshott_coparent_links enable row level security;
alter table public.yshott_calendar_events enable row level security;
alter table public.yshott_calendar_grants enable row level security;
alter table yshott_private.family_codes enable row level security;
alter table yshott_private.join_attempts enable row level security;
revoke all on all tables in schema yshott_private from public, anon, authenticated;
revoke all on public.yshott_households,public.yshott_members,public.yshott_state,
  public.yshott_coparent_links,public.yshott_calendar_events,public.yshott_calendar_grants
  from public,anon,authenticated;
grant select on public.yshott_households,public.yshott_members,public.yshott_state,
  public.yshott_coparent_links,public.yshott_calendar_events,public.yshott_calendar_grants
  to authenticated;

-- Private definers exist only for atomic operations and non-recursive membership checks.
-- They always resolve the caller from auth.uid(), never from caller-supplied user metadata.
create function yshott_private.verified_user() returns uuid
language plpgsql security definer set search_path = '' as $$
declare actor uuid := auth.uid();
begin
  if actor is null or not exists (
    select 1 from auth.users where id=actor and email_confirmed_at is not null
      and coalesce(is_anonymous,false)=false
  ) then raise exception 'Verified email required' using errcode='42501'; end if;
  return actor;
end $$;
create function yshott_private.is_member(family uuid) returns boolean
language sql stable security definer set search_path = '' as $$
  select auth.uid() is not null and exists (
    select 1 from public.yshott_members
    where household_id=family and user_id=(select auth.uid()) and status='active'
  );
$$;
create function yshott_private.is_admin(family uuid) returns boolean
language sql stable security definer set search_path = '' as $$
  select auth.uid() is not null and exists (
    select 1 from public.yshott_members
    where household_id=family and user_id=(select auth.uid()) and status='active' and role='admin'
  );
$$;
create function yshott_private.linked(a uuid,b uuid) returns boolean
language sql stable security definer set search_path = '' as $$
  select auth.uid() is not null and
    (yshott_private.is_member(a) or yshott_private.is_member(b)) and exists (
      select 1 from public.yshott_coparent_links
      where status='active' and
        ((source_household=a and target_household=b) or (source_household=b and target_household=a))
    );
$$;
create function yshott_private.can_read_event(event uuid) returns boolean
language sql stable security definer set search_path = '' as $$
  select auth.uid() is not null and exists (
    select 1 from public.yshott_calendar_events e
    where e.id=event and (yshott_private.is_member(e.household_id) or exists (
      select 1 from public.yshott_calendar_grants g
      where g.event_id=e.id and yshott_private.is_member(g.recipient_household)
        and yshott_private.linked(e.household_id,g.recipient_household)
    ))
  );
$$;
create policy household_read on public.yshott_households for select to authenticated
  using (yshott_private.is_member(id));
create policy membership_read on public.yshott_members for select to authenticated
  using (user_id=(select auth.uid()) or yshott_private.is_admin(household_id));
create policy state_read on public.yshott_state for select to authenticated
  using (yshott_private.is_member(household_id));
create policy coparent_read on public.yshott_coparent_links for select to authenticated
  using (yshott_private.is_member(source_household) or yshott_private.is_member(target_household));
create policy event_read on public.yshott_calendar_events for select to authenticated
  using (yshott_private.can_read_event(id));
create policy calendar_grants_read on public.yshott_calendar_grants for select to authenticated
  using (yshott_private.can_read_event(event_id));

create function yshott_private.rotate_code(family uuid) returns text
language plpgsql security definer set search_path = '' as $$
declare result text;
begin
  perform yshott_private.verified_user();
  if not yshott_private.is_admin(family) then raise exception 'Admin required' using errcode='42501'; end if;
  result := upper(encode(extensions.gen_random_bytes(10),'hex'));
  insert into yshott_private.family_codes(household_id,code_hash,expires_at)
  values(family,extensions.digest(result,'sha256'),now()+interval '7 days')
  on conflict(household_id) do update set code_hash=excluded.code_hash,
    expires_at=excluded.expires_at,created_at=now();
  return result;
end $$;
create function yshott_private.create_family(family_name text,display_name text) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare actor uuid := yshott_private.verified_user(); family uuid; invitation text;
begin
  -- Serialize per account so concurrent requests cannot bypass the family limit.
  perform pg_advisory_xact_lock(hashtextextended(actor::text,0));
  if (select count(*) from public.yshott_households where created_by=actor) >= 10 then
    raise exception 'Household limit reached' using errcode='22023';
  end if;
  insert into public.yshott_households(name,created_by) values(trim(family_name),actor) returning id into family;
  insert into public.yshott_members(household_id,user_id,display_name,role,status,approved_by)
    values(family,actor,trim(display_name),'admin','active',actor);
  insert into public.yshott_state(household_id,updated_by) values(family,actor);
  invitation := yshott_private.rotate_code(family);
  return jsonb_build_object('household_id',family,'code',invitation,'expires_in_days',7);
end $$;
create function yshott_private.request_join(invitation text,display_name text,relationship text,own_household uuid) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare actor uuid := yshott_private.verified_user(); family uuid; link_id uuid; existing text;
begin
  perform pg_advisory_xact_lock(hashtextextended(actor::text,0));
  if (select count(*) from yshott_private.join_attempts where user_id=actor and attempted_at>now()-interval '1 hour') >= 5 then
    return jsonb_build_object('status','rate_limited');
  end if;
  insert into yshott_private.join_attempts(user_id) values(actor);
  if relationship not in ('partner','co_parent') or relationship is null
    or length(trim(display_name)) not between 1 and 80 then return jsonb_build_object('status','invalid'); end if;
  select household_id into family from yshott_private.family_codes
    where code_hash=extensions.digest(upper(trim(invitation)),'sha256') and expires_at>now();
  if family is null then return jsonb_build_object('status','invalid'); end if;
  if relationship='co_parent' then
    if own_household is null or own_household=family or not yshott_private.is_admin(own_household) then
      return jsonb_build_object('status','own_household_required');
    end if;
    -- A co-parent links separate households and never becomes a member of the other's household.
    insert into public.yshott_coparent_links(source_household,target_household,requested_by)
      values(own_household,family,actor) on conflict do nothing returning id into link_id;
    if link_id is null then return jsonb_build_object('status','already_requested'); end if;
    return jsonb_build_object('status','pending','link_id',link_id);
  end if;
  select status into existing from public.yshott_members where household_id=family and user_id=actor;
  if existing is not null then return jsonb_build_object('status',existing,'household_id',family); end if;
  insert into public.yshott_members(household_id,user_id,display_name,role,status)
    values(family,actor,trim(display_name),'adult','pending');
  return jsonb_build_object('status','pending','household_id',family);
end $$;
create function yshott_private.decide_member(family uuid,applicant uuid,accept boolean) returns void
language plpgsql security definer set search_path = '' as $$
begin
  perform yshott_private.verified_user();
  if not yshott_private.is_admin(family) then raise exception 'Admin required' using errcode='42501'; end if;
  if accept is null then raise exception 'Decision required' using errcode='22023'; end if;
  update public.yshott_members set status=case when accept then 'active' else 'rejected' end,approved_by=auth.uid()
    where household_id=family and user_id=applicant and status='pending' and role='adult';
  if not found then raise exception 'Pending member not found' using errcode='22023'; end if;
end $$;
create function yshott_private.revoke_member(family uuid,member uuid) returns void
language plpgsql security definer set search_path = '' as $$
begin
  perform yshott_private.verified_user();
  if not yshott_private.is_admin(family) then raise exception 'Admin required' using errcode='42501'; end if;
  update public.yshott_members set status='revoked'
    where household_id=family and user_id=member and role='adult';
end $$;
create function yshott_private.decide_link(link uuid,accept boolean) returns void
language plpgsql security definer set search_path = '' as $$
declare target uuid;
begin
  perform yshott_private.verified_user();
  select target_household into target from public.yshott_coparent_links where id=link and status='pending' for update;
  if target is null or not yshott_private.is_admin(target) then raise exception 'Receiving admin required' using errcode='42501'; end if;
  if accept is null then raise exception 'Decision required' using errcode='22023'; end if;
  update public.yshott_coparent_links set status=case when accept then 'active' else 'rejected' end,decided_by=auth.uid() where id=link;
end $$;
create function yshott_private.revoke_link(link uuid) returns void
language plpgsql security definer set search_path = '' as $$
declare item public.yshott_coparent_links;
begin
  perform yshott_private.verified_user();
  select * into item from public.yshott_coparent_links where id=link for update;
  if item.id is null or not (yshott_private.is_admin(item.source_household) or yshott_private.is_admin(item.target_household)) then
    raise exception 'Admin required' using errcode='42501';
  end if;
  -- Clear exceptions in both directions: reconnecting must not revive old full-details grants.
  delete from public.yshott_calendar_grants g using public.yshott_calendar_events e
  where g.event_id=e.id and (
    (e.household_id=item.source_household and g.recipient_household=item.target_household) or
    (e.household_id=item.target_household and g.recipient_household=item.source_household)
  );
  update public.yshott_coparent_links set status='revoked',decided_by=auth.uid() where id=link;
end $$;
create function yshott_private.save_state(family uuid,expected_revision bigint,data jsonb) returns bigint
language plpgsql security definer set search_path = '' as $$
declare current_revision bigint; next_revision bigint; key text;
begin
  perform yshott_private.verified_user();
  if not yshott_private.is_member(family) then raise exception 'Active member required' using errcode='42501'; end if;
  if data is null or jsonb_typeof(data)<>'object' or octet_length(data::text)>1000000 then
    raise exception 'Invalid planner' using errcode='22023';
  end if;
  foreach key in array array['people','events','tasks','groceries','recipes','meals','rewards','rewardRequests','messages','proposals','residence'] loop
    if data ? key and jsonb_typeof(data->key)<>'array' then raise exception 'Invalid planner collection' using errcode='22023'; end if;
  end loop;
  select revision into current_revision from public.yshott_state where household_id=family for update;
  if current_revision is null or expected_revision is distinct from current_revision then
    raise exception 'Planner changed; fetch the latest version before saving' using errcode='40001';
  end if;
  next_revision := current_revision+1;
  update public.yshott_state set revision=next_revision,payload=data,updated_by=auth.uid(),updated_at=now()
    where household_id=family;
  return next_revision;
end $$;
create function yshott_private.calendar_for(recipient uuid,from_time timestamptz,to_time timestamptz) returns jsonb
language plpgsql security definer set search_path = '' as $$
begin
  perform yshott_private.verified_user();
  if not yshott_private.is_member(recipient) then raise exception 'Member required' using errcode='42501'; end if;
  if from_time is null or to_time is null or to_time<=from_time or to_time-from_time>interval '93 days' then
    raise exception 'Choose a range of at most 93 days' using errcode='22023';
  end if;
  return coalesce((select jsonb_agg(jsonb_build_object(
    'id',e.id,'starts_at',e.starts_at,'ends_at',e.ends_at,
    'title',case when full_details then e.title else 'Bezet' end,
    'location',case when full_details then e.location else '' end,
    'notes',case when full_details then e.notes else '' end,
    'full_details',full_details
  ) order by e.starts_at,e.id) from (
    select e.*, e.household_id=recipient or exists (
      select 1 from public.yshott_calendar_grants g where g.event_id=e.id and g.recipient_household=recipient
    ) as full_details
    from public.yshott_calendar_events e
    where e.ends_at>from_time and e.starts_at<to_time
      and (e.household_id=recipient or yshott_private.linked(e.household_id,recipient))
  ) e),'[]'::jsonb);
end $$;
create function yshott_private.save_event(event uuid,family uuid,title text,starts_at timestamptz,ends_at timestamptz,location text,notes text) returns uuid
language plpgsql security definer set search_path='' as $$
declare actor uuid:=yshott_private.verified_user(); item uuid:=coalesce(event,gen_random_uuid());
begin
  if not yshott_private.is_member(family) then raise exception 'Member required' using errcode='42501'; end if;
  insert into public.yshott_calendar_events as existing(id,household_id,owner_id,title,starts_at,ends_at,location,notes)
    values(item,family,actor,title,starts_at,ends_at,coalesce(location,''),coalesce(notes,''))
  on conflict(id) do update set title=excluded.title,starts_at=excluded.starts_at,ends_at=excluded.ends_at,
    location=excluded.location,notes=excluded.notes
    where existing.household_id=family and (existing.owner_id=actor or yshott_private.is_admin(existing.household_id));
  if not found then raise exception 'Event owner required' using errcode='42501'; end if;
  return item;
end $$;
create function yshott_private.share_event(event uuid,recipient uuid,full_details boolean) returns void
language plpgsql security definer set search_path='' as $$
declare actor uuid:=yshott_private.verified_user(); item public.yshott_calendar_events;
begin
  select * into item from public.yshott_calendar_events where id=event for update;
  if item.id is null or not yshott_private.is_member(item.household_id) or
    not (item.owner_id=actor or yshott_private.is_admin(item.household_id)) then
    raise exception 'Event owner required' using errcode='42501';
  end if;
  if full_details is null then raise exception 'Sharing decision required' using errcode='22023'; end if;
  if full_details then
    if not yshott_private.linked(item.household_id,recipient) then raise exception 'Active link required' using errcode='42501'; end if;
    insert into public.yshott_calendar_grants(event_id,recipient_household,granted_by)
      values(event,recipient,actor) on conflict(event_id,recipient_household) do nothing;
  else
    delete from public.yshott_calendar_grants where event_id=event and recipient_household=recipient;
  end if;
end $$;
create function public.yshott_save_event(event uuid,family uuid,title text,starts_at timestamptz,ends_at timestamptz,location text default '',notes text default '') returns uuid
language sql security invoker set search_path='' as $$ select yshott_private.save_event(event,family,title,starts_at,ends_at,location,notes); $$;
create function public.yshott_share_event(event uuid,recipient uuid,full_details boolean) returns void
language sql security invoker set search_path='' as $$ select yshott_private.share_event(event,recipient,full_details); $$;
revoke execute on function public.yshott_save_event(uuid,uuid,text,timestamptz,timestamptz,text,text),
 public.yshott_share_event(uuid,uuid,boolean) from public,anon;
grant execute on function public.yshott_save_event(uuid,uuid,text,timestamptz,timestamptz,text,text),
 public.yshott_share_event(uuid,uuid,boolean) to authenticated;
-- Definers stay private; exposed wrappers run as the authenticated caller.
create function public.yshott_create_family(family_name text,display_name text) returns jsonb
language sql security invoker set search_path='' as $$ select yshott_private.create_family(family_name,display_name); $$;
create function public.yshott_rotate_code(family uuid) returns text
language sql security invoker set search_path='' as $$ select yshott_private.rotate_code(family); $$;
create function public.yshott_request_join(invitation text,display_name text,relationship text,own_household uuid default null) returns jsonb
language sql security invoker set search_path='' as $$ select yshott_private.request_join(invitation,display_name,relationship,own_household); $$;
create function public.yshott_decide_member(family uuid,applicant uuid,accept boolean) returns void
language sql security invoker set search_path='' as $$ select yshott_private.decide_member(family,applicant,accept); $$;
create function public.yshott_revoke_member(family uuid,member uuid) returns void
language sql security invoker set search_path='' as $$ select yshott_private.revoke_member(family,member); $$;
create function public.yshott_decide_link(link uuid,accept boolean) returns void
language sql security invoker set search_path='' as $$ select yshott_private.decide_link(link,accept); $$;
create function public.yshott_revoke_link(link uuid) returns void
language sql security invoker set search_path='' as $$ select yshott_private.revoke_link(link); $$;
create function public.yshott_save_state(family uuid,expected_revision bigint,data jsonb) returns bigint
language sql security invoker set search_path='' as $$ select yshott_private.save_state(family,expected_revision,data); $$;
create function public.yshott_calendar_for(recipient uuid,from_time timestamptz,to_time timestamptz) returns jsonb
language sql security invoker set search_path='' as $$ select yshott_private.calendar_for(recipient,from_time,to_time); $$;

revoke all on all functions in schema yshott_private from public,anon,authenticated;
grant execute on all functions in schema yshott_private to authenticated;
revoke execute on function public.yshott_create_family(text,text),public.yshott_rotate_code(uuid),
  public.yshott_request_join(text,text,text,uuid),public.yshott_decide_member(uuid,uuid,boolean),
  public.yshott_revoke_member(uuid,uuid),public.yshott_decide_link(uuid,boolean),public.yshott_revoke_link(uuid),
  public.yshott_save_state(uuid,bigint,jsonb),public.yshott_calendar_for(uuid,timestamptz,timestamptz)
  from public,anon;
grant execute on function public.yshott_create_family(text,text),public.yshott_rotate_code(uuid),
  public.yshott_request_join(text,text,text,uuid),public.yshott_decide_member(uuid,uuid,boolean),
  public.yshott_revoke_member(uuid,uuid),public.yshott_decide_link(uuid,boolean),public.yshott_revoke_link(uuid),
  public.yshott_save_state(uuid,bigint,jsonb),public.yshott_calendar_for(uuid,timestamptz,timestamptz)
  to authenticated;
commit;
