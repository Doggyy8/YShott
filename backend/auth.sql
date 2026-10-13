-- Apply after backend/schema.sql. Server-only PIN credential derivation and throttling.
begin;
create table yshott_private.auth_secret (
  singleton boolean primary key default true check(singleton),
  pepper bytea not null check(octet_length(pepper)=32)
);
insert into yshott_private.auth_secret(singleton,pepper) values(true,extensions.gen_random_bytes(32));
create table yshott_private.auth_attempts (
  identity_hash text not null,
  purpose text not null check(purpose in ('login','register','recover','reset')),
  window_started timestamptz not null,
  window_count integer not null default 0 check(window_count>=0),
  day_started timestamptz not null,
  day_count integer not null default 0 check(day_count>=0),
  primary key(identity_hash,purpose)
);
alter table yshott_private.auth_secret enable row level security;
alter table yshott_private.auth_attempts enable row level security;
revoke all on yshott_private.auth_secret,yshott_private.auth_attempts from public,anon,authenticated,service_role;
grant usage on schema yshott_private,extensions to service_role;
grant select on yshott_private.auth_secret to service_role;
grant select,insert,update on yshott_private.auth_attempts to service_role;
-- Invoker: only the backend service role has privileges. There is no public PIN oracle.
create function yshott_private.prepare_auth(email text,pin text,purpose text) returns jsonb
language plpgsql security invoker set search_path='' as $$
declare normalized text:=lower(trim(email)); secret bytea; identity text;
  state yshott_private.auth_attempts; span interval; max_window integer; max_day integer; password text;
begin
  if normalized is null or length(normalized) not between 5 and 254
    or normalized !~ '^[^[:space:]@]+@[^[:space:]@]+\.[^[:space:]@]+$'
    or purpose is null or purpose not in ('login','register','recover','reset') then
    raise exception 'Invalid authentication request' using errcode='22023';
  end if;
  if purpose<>'recover' and (pin is null or pin !~ '^[0-9]{4}$') then
    raise exception 'Invalid PIN' using errcode='22023';
  end if;
  select pepper into strict secret from yshott_private.auth_secret where singleton;
  identity:=encode(extensions.hmac(convert_to('rate:'||normalized,'UTF8'),secret,'sha256'),'hex');
  if purpose in ('register','recover') then span:=interval '1 hour'; max_window:=2; max_day:=4;
  else span:=interval '15 minutes'; max_window:=5; max_day:=20; end if;
  insert into yshott_private.auth_attempts(identity_hash,purpose,window_started,day_started)
    values(identity,purpose,now(),now()) on conflict do nothing;
  select * into strict state from yshott_private.auth_attempts a
    where a.identity_hash=identity and a.purpose=prepare_auth.purpose for update;
  if state.window_started+span<=now() then state.window_started:=now(); state.window_count:=0; end if;
  if state.day_started+interval '24 hours'<=now() then state.day_started:=now(); state.day_count:=0; end if;
  if state.window_count>=max_window or state.day_count>=max_day then
    return jsonb_build_object('allowed',false);
  end if;
  update yshott_private.auth_attempts a set window_started=state.window_started,window_count=state.window_count+1,
    day_started=state.day_started,day_count=state.day_count+1
    where a.identity_hash=identity and a.purpose=prepare_auth.purpose;
  if purpose<>'recover' then
    password:=encode(extensions.hmac(convert_to('credential:'||normalized||':'||pin,'UTF8'),secret,'sha256'),'hex');
  end if;
  return jsonb_build_object('allowed',true,'password',password);
end $$;
create function public.yshott_prepare_auth(email text,pin text,purpose text) returns jsonb
language sql security invoker set search_path='' as $$ select yshott_private.prepare_auth(email,pin,purpose); $$;
revoke execute on function yshott_private.prepare_auth(text,text,text),public.yshott_prepare_auth(text,text,text)
  from public,anon,authenticated,service_role;
grant execute on function yshott_private.prepare_auth(text,text,text),public.yshott_prepare_auth(text,text,text)
  to service_role;
create function yshott_private.finish_recovery(email text) returns void
language plpgsql security invoker set search_path='' as $$
declare secret bytea; identity text;
begin
 select pepper into strict secret from yshott_private.auth_secret where singleton;
 identity:=encode(extensions.hmac(convert_to('rate:'||lower(trim(email)),'UTF8'),secret,'sha256'),'hex');
 update yshott_private.auth_attempts set window_count=0,day_count=0,window_started=now(),day_started=now()
 where identity_hash=identity and purpose='login';
end $$;
create function public.yshott_finish_recovery(email text) returns void
language sql security invoker set search_path='' as $$ select yshott_private.finish_recovery(email); $$;
revoke execute on function yshott_private.finish_recovery(text),public.yshott_finish_recovery(text)
 from public,anon,authenticated,service_role;
grant execute on function yshott_private.finish_recovery(text),public.yshott_finish_recovery(text) to service_role;
commit;
