-- Ensure public schema exists and accessible
CREATE SCHEMA IF NOT EXISTS public;
GRANT ALL ON SCHEMA public TO gb_user;
GRANT ALL ON SCHEMA public TO PUBLIC;
