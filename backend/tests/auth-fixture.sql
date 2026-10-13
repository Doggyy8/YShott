-- CI only: minimal Supabase Auth contract. Never deploy this fixture to Supabase.
create role anon nologin;
create role authenticated nologin;
create role service_role nologin bypassrls;
create schema auth;
create table auth.users (
 id uuid primary key,
 email text not null,
 email_confirmed_at timestamptz,
 is_anonymous boolean not null default false
);
create function auth.uid() returns uuid language sql stable as $$
 select nullif(current_setting('request.jwt.claim.sub',true),'')::uuid;
$$;
grant usage on schema auth to authenticated;
grant execute on function auth.uid() to authenticated;
grant usage on schema public to anon,authenticated;
