-- The audit log module's tables the Logs page reads, as the tests need them: the test context does not
-- run that module's liquibase. Envers makes the platform's audit tables.
create table if not exists read_audit_log (id int auto_increment primary key, entity_name varchar(255) not null, read_success boolean not null, username varchar(50), user_uuid varchar(38) not null, event_time datetime not null, ip_address varchar(50), user_agent varchar(500), session_id varchar(100), uuid char(38) not null);
create table if not exists read_audit_entity_metadata (id bigint auto_increment primary key, read_audit_id int not null, entity_uuid varchar(38) not null, patient_uuid varchar(38), patient_name varchar(255));
