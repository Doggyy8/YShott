-- Fail-fast integration tests; run against a disposable database only.
\set ON_ERROR_STOP on
begin;
insert into auth.users(id,email,email_confirmed_at) values
 ('00000000-0000-0000-0000-000000000001','parent-a@example.invalid',now()),
 ('00000000-0000-0000-0000-000000000002','parent-b@example.invalid',now()),
 ('00000000-0000-0000-0000-000000000003','partner@example.invalid',now()),
 ('00000000-0000-0000-0000-000000000004','unrelated@example.invalid',now()),
 ('00000000-0000-0000-0000-000000000005','unverified@example.invalid',null);

create function pg_temp.assert_true(ok boolean,label text) returns void language plpgsql as $$
begin if ok is distinct from true then raise exception 'FAIL: %',label; end if; raise notice 'PASS: %',label; end $$;
-- Assertions run as caller. Expected failures catch only the precise error, never their own assertion.
create function pg_temp.denied(command text,label text) returns void language plpgsql security invoker as $$
declare blocked boolean:=false;
begin
 begin execute command; exception when insufficient_privilege then blocked:=true; end;
 perform pg_temp.assert_true(blocked,label);
end $$;
set local role anon;
select pg_temp.denied('select public.yshott_create_family(''X'',''X'')','Anonymous cannot create families');
select pg_temp.denied('select * from public.yshott_state','Anonymous cannot read planner');
reset role;

set local role authenticated;
select set_config('request.jwt.claim.sub','00000000-0000-0000-0000-000000000005',true);
select pg_temp.denied('select public.yshott_create_family(''X'',''X'')','Unverified email cannot create family');

select set_config('request.jwt.claim.sub','00000000-0000-0000-0000-000000000001',true);
select public.yshott_create_family('Parent A','A') as family_a \gset
select (:'family_a'::jsonb->>'household_id') as a,(:'family_a'::jsonb->>'code') as code_a \gset
select pg_temp.assert_true((select count(*)=1 from public.yshott_households),'Creator sees only own family');
select public.yshott_save_state(:'a',0,'{"people":[],"events":[],"tasks":[],"groceries":[{"name":"Private groceries"}]}') as revision \gset
select pg_temp.assert_true(:revision=1,'Planner revision advances atomically');
do $$ declare rejected boolean:=false;
begin
 begin
  perform public.yshott_save_state((select id from public.yshott_households limit 1),0,'{}');
 exception when serialization_failure then rejected:=true; end;
 perform pg_temp.assert_true(rejected,'Stale writes cannot overwrite newer data');
end $$;
select pg_temp.denied('update public.yshott_members set role=''admin''','Direct membership changes denied');
select public.yshott_save_event(null,:'a','Private appointment','2026-10-13 09:00+02','2026-10-13 10:00+02','Secret address','Secret note') as event_a \gset

select set_config('request.jwt.claim.sub','00000000-0000-0000-0000-000000000002',true);
select public.yshott_create_family('Parent B','B') as family_b \gset
select (:'family_b'::jsonb->>'household_id') as b \gset
select pg_temp.assert_true((select count(*)=1 from public.yshott_households),'Other family is hidden');
select pg_temp.assert_true(not exists(select 1 from public.yshott_state where household_id=:'a'),'Other planner is hidden');
select pg_temp.denied(format('select public.yshott_save_state(%L,1,''{}'')',:'a'),'Cross-family write denied');
select public.yshott_request_join(:'code_a','B','co_parent',:'b') as link_result \gset
select (:'link_result'::jsonb->>'link_id') as link \gset
select pg_temp.assert_true(:'link_result'::jsonb->>'status'='pending','Co-parent link waits for approval');
select pg_temp.assert_true(not exists(select 1 from public.yshott_members where household_id=:'a'),'Co-parent is not a private family member');
select pg_temp.assert_true(public.yshott_calendar_for(:'b','2026-10-13 00:00+02','2026-10-14 00:00+02')='[]','Pending link grants no calendar access');
select pg_temp.denied(format('select public.yshott_decide_link(%L,true)',:'link'),'Requester cannot approve own link');

select set_config('request.jwt.claim.sub','00000000-0000-0000-0000-000000000001',true);
select public.yshott_decide_link(:'link',true);
select set_config('request.jwt.claim.sub','00000000-0000-0000-0000-000000000002',true);
select public.yshott_calendar_for(:'b','2026-10-13 00:00+02','2026-10-14 00:00+02') as busy \gset
select pg_temp.assert_true(:'busy'::jsonb->0->>'title'='Bezet','Co-parent defaults to busy/free');
select pg_temp.assert_true(:'busy'::jsonb->0->>'location'='' and :'busy'::jsonb->0->>'notes'='','Busy/free removes private details on server');
select pg_temp.assert_true(not exists(select 1 from public.yshott_calendar_events where id=:'event_a'),'Direct query cannot bypass busy/free masking');
select pg_temp.assert_true(not exists(select 1 from public.yshott_state where household_id=:'a'),'Link does not expose groceries or family chat');
select pg_temp.denied(format('select public.yshott_share_event(%L,%L,true)',:'event_a',:'b'),'Co-parent cannot grant access to another parent event');

select set_config('request.jwt.claim.sub','00000000-0000-0000-0000-000000000001',true);
select public.yshott_share_event(:'event_a',:'b',true);
select set_config('request.jwt.claim.sub','00000000-0000-0000-0000-000000000002',true);
select pg_temp.assert_true((select title='Private appointment' from public.yshott_calendar_events where id=:'event_a'),'Explicit full-details exception works');
select public.yshott_calendar_for(:'b','2026-10-13 00:00+02','2026-10-14 00:00+02') as shared \gset
select pg_temp.assert_true(:'shared'::jsonb->0->>'location'='Secret address','Explicit exception includes full details');
select set_config('request.jwt.claim.sub','00000000-0000-0000-0000-000000000001',true);
select public.yshott_share_event(:'event_a',:'b',false);
select set_config('request.jwt.claim.sub','00000000-0000-0000-0000-000000000002',true);
select pg_temp.assert_true(not exists(select 1 from public.yshott_calendar_events where id=:'event_a'),'Revoked exception applies without JWT refresh');

select set_config('request.jwt.claim.sub','00000000-0000-0000-0000-000000000003',true);
select public.yshott_request_join(:'code_a','Partner','partner',null) as joined \gset
select pg_temp.assert_true(:'joined'::jsonb->>'status'='pending','Partner joins pending');
select pg_temp.assert_true(not exists(select 1 from public.yshott_state),'Pending partner has no private planner');
select pg_temp.denied(format('select public.yshott_decide_member(%L,''00000000-0000-0000-0000-000000000003'',true)',:'a'),'Partner cannot self-approve');

select set_config('request.jwt.claim.sub','00000000-0000-0000-0000-000000000001',true);
select public.yshott_decide_member(:'a','00000000-0000-0000-0000-000000000003',true);
select set_config('request.jwt.claim.sub','00000000-0000-0000-0000-000000000003',true);
select pg_temp.assert_true((select title='Private appointment' from public.yshott_calendar_events where id=:'event_a'),'Approved household partner sees full appointments');
select pg_temp.assert_true((select revision=1 from public.yshott_state where household_id=:'a'),'Approved household partner sees shared planner');
select pg_temp.denied(format('select public.yshott_rotate_code(%L)',:'a'),'Regular partner cannot rotate admin code');

select set_config('request.jwt.claim.sub','00000000-0000-0000-0000-000000000001',true);
select public.yshott_revoke_member(:'a','00000000-0000-0000-0000-000000000003');
select public.yshott_share_event(:'event_a',:'b',true);
select public.yshott_revoke_link(:'link');
select set_config('request.jwt.claim.sub','00000000-0000-0000-0000-000000000002',true);
select pg_temp.assert_true(not exists(select 1 from public.yshott_calendar_events where id=:'event_a'),'Link revocation also removes active full-details exceptions');
select public.yshott_request_join(:'code_a','B','co_parent',:'b') as reconnect_result \gset
select (:'reconnect_result'::jsonb->>'link_id') as reconnect \gset
select set_config('request.jwt.claim.sub','00000000-0000-0000-0000-000000000001',true);
select public.yshott_decide_link(:'reconnect',true);
select set_config('request.jwt.claim.sub','00000000-0000-0000-0000-000000000002',true);
select public.yshott_calendar_for(:'b','2026-10-13 00:00+02','2026-10-14 00:00+02') as reconnected_calendar \gset
select pg_temp.assert_true(:'reconnected_calendar'::jsonb->0->>'title'='Bezet','Reconnecting cannot revive old full-details exceptions');
select set_config('request.jwt.claim.sub','00000000-0000-0000-0000-000000000001',true);
select public.yshott_revoke_link(:'reconnect');
select public.yshott_rotate_code(:'a') as new_code \gset
select pg_temp.assert_true(:'new_code'<>:'code_a','Rotation changes unique invitation');
select set_config('request.jwt.claim.sub','00000000-0000-0000-0000-000000000003',true);
select pg_temp.assert_true(not exists(select 1 from public.yshott_state),'Revoked membership applies without JWT refresh');
select set_config('request.jwt.claim.sub','00000000-0000-0000-0000-000000000002',true);
select pg_temp.assert_true(public.yshott_calendar_for(:'b','2026-10-13 00:00+02','2026-10-14 00:00+02')='[]','Revoked co-parent link removes calendar access');

select set_config('request.jwt.claim.sub','00000000-0000-0000-0000-000000000004',true);
select pg_temp.assert_true(public.yshott_request_join(:'code_a','X','partner',null)->>'status'='invalid','Rotated invitation is invalid');
select public.yshott_request_join('BAD','X','partner',null);
select public.yshott_request_join('BAD','X','partner',null);
select public.yshott_request_join('BAD','X','partner',null);
select public.yshott_request_join('BAD','X','partner',null);
select pg_temp.assert_true(public.yshott_request_join(:'new_code','X','partner',null)->>'status'='rate_limited','Repeated guesses are rate limited even with valid next code');
select pg_temp.assert_true(not exists(select 1 from public.yshott_state),'Unrelated user sees no planner');
reset role;
select pg_temp.assert_true(not exists(
 select 1 from pg_tables where schemaname in ('public','yshott_private') and not rowsecurity
),'Every app table has RLS');
select pg_temp.assert_true(not exists(
 select 1 from pg_proc p join pg_namespace n on n.oid=p.pronamespace
 where n.nspname='public' and p.proname like 'yshott_%' and p.prosecdef
),'No public security-definer endpoints');
select pg_temp.assert_true(not has_table_privilege('authenticated','yshott_private.family_codes','select'),'Invitation hashes are private');
rollback;
