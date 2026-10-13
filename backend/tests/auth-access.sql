\set ON_ERROR_STOP on
begin;
create function pg_temp.assert_true(ok boolean,label text) returns void language plpgsql as $$
begin if ok is distinct from true then raise exception 'FAIL: %',label; end if; raise notice 'PASS: %',label; end $$;
create function pg_temp.denied(command text,label text) returns void language plpgsql security invoker as $$
declare blocked boolean:=false;
begin begin execute command; exception when insufficient_privilege then blocked:=true; end;
perform pg_temp.assert_true(blocked,label); end $$;
set local role anon;
select pg_temp.denied('select public.yshott_prepare_auth(''a@example.invalid'',''1234'',''login'')','Anonymous cannot derive credentials');
reset role;
set local role authenticated;
select pg_temp.denied('select public.yshott_prepare_auth(''a@example.invalid'',''1234'',''login'')','Signed-in client cannot derive credentials');
select pg_temp.denied('select public.yshott_finish_recovery(''a@example.invalid'')','Clients cannot unlock PIN rate limits');
select pg_temp.denied('select pepper from yshott_private.auth_secret','Clients cannot read PIN pepper');
reset role;
set local role service_role;
select public.yshott_prepare_auth('A@example.invalid','1234','login') as first_result \gset
select public.yshott_prepare_auth(' a@example.invalid ','1234','login') as same_result \gset
select pg_temp.assert_true(:'first_result'::jsonb->>'password'=:'same_result'::jsonb->>'password','Email normalization preserves login credential');
select pg_temp.assert_true(length(:'first_result'::jsonb->>'password')=64,'Supabase receives a secret-derived strong credential');
select public.yshott_prepare_auth('a@example.invalid','5678','login') as different_result \gset
select pg_temp.assert_true(:'first_result'::jsonb->>'password'<>:'different_result'::jsonb->>'password','Different PIN gives a different credential');
select public.yshott_prepare_auth('b@example.invalid','1234','login') as other_result \gset
select pg_temp.assert_true(:'first_result'::jsonb->>'password'<>:'other_result'::jsonb->>'password','Same PIN across accounts does not reuse a credential');
select public.yshott_prepare_auth('a@example.invalid','0000','login');
select public.yshott_prepare_auth('a@example.invalid','0001','login');
select pg_temp.assert_true(public.yshott_prepare_auth('a@example.invalid','1234','login')->>'allowed'='false','Sixth PIN attempt is throttled');
select pg_temp.assert_true(not(public.yshott_prepare_auth('a@example.invalid','1234','login') ? 'password'),'Throttled request returns no credential');
select pg_temp.assert_true(public.yshott_prepare_auth('a@example.invalid',null,'recover')->>'allowed'='true','Email recovery remains possible during PIN lockout');
select public.yshott_finish_recovery('a@example.invalid');
select pg_temp.assert_true(public.yshott_prepare_auth('a@example.invalid','1234','login')->>'allowed'='true','Verified server recovery unlocks PIN login');
update yshott_private.auth_attempts set day_count=20,window_count=0 where purpose='login';
select pg_temp.assert_true(public.yshott_prepare_auth('a@example.invalid','1234','login')->>'allowed'='false','Daily attempt limit survives a fresh short window');
select pg_temp.assert_true((select count(*)=2 from yshott_private.auth_attempts where purpose='login'),'Attempts are grouped per normalized account');
reset role;
select pg_temp.assert_true(not has_table_privilege('service_role','yshott_private.auth_secret','update'),'Auth handler cannot silently change the pepper');
select pg_temp.assert_true((select count(*)=1 from yshott_private.auth_secret),'Only one persistent pepper exists');
rollback;
