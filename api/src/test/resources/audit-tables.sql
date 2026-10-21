-- The audit log module's tables and the platform's Envers tables the Logs page reads, as the tests
-- need them: the test context runs neither Envers nor the module's liquibase.
create table if not exists revision_entity (id int primary key, timestamp bigint, changedBy int, changedOn datetime);
create table if not exists encounter_AUD (encounter_id int, REV int, REVTYPE tinyint, uuid varchar(38), voided boolean, void_reason varchar(255), encounter_datetime datetime, encounter_type int, form_id int, location_id int, patient_id int, visit_id int);
create table if not exists obs_AUD (obs_id int, REV int, REVTYPE tinyint, uuid varchar(38), person_id int, obs_datetime datetime, value_datetime datetime, value_numeric double, value_text clob, value_complex varchar(1000), voided boolean, void_reason varchar(255), concept_id int, value_coded int, value_coded_name_id int, value_drug int, encounter_id int, obs_group_id int, previous_version int, location_id int);
create table if not exists person_AUD (person_id int, REV int, REVTYPE tinyint, uuid varchar(38), gender varchar(50), birthdate datetime, dead boolean, death_date datetime, voided boolean, cause_of_death int);
create table if not exists patient_AUD (patient_id int, REV int, voided boolean);
create table if not exists person_name_AUD (person_name_id int, REV int, REVTYPE tinyint, uuid varchar(38), preferred boolean, given_name varchar(50), middle_name varchar(50), family_name varchar(50), voided boolean, person_id int);
create table if not exists person_address_AUD (person_address_id int, REV int, REVTYPE tinyint, uuid varchar(38), address1 varchar(255), city_village varchar(255), voided boolean, person_id int);
create table if not exists person_attribute_AUD (person_attribute_id int, REV int, REVTYPE tinyint, uuid varchar(38), value varchar(50), voided boolean, person_id int, person_attribute_type_id int);
create table if not exists patient_identifier_AUD (patient_identifier_id int, REV int, REVTYPE tinyint, uuid varchar(38), identifier varchar(50), preferred boolean, voided boolean, patient_id int, identifier_type int, location_id int);
create table if not exists patient_program_AUD (patient_program_id int, REV int, REVTYPE tinyint, uuid varchar(38), date_enrolled datetime, date_completed datetime, voided boolean, void_reason varchar(255), outcome_concept_id int, program_id int, patient_id int, location_id int);
create table if not exists patient_state_AUD (patient_state_id int, REV int, REVTYPE tinyint, uuid varchar(38), voided boolean, void_reason varchar(255), end_date datetime, start_date datetime, patient_program_id int, state int);
create table if not exists users_AUD (user_id int, REV int, REVTYPE tinyint, uuid varchar(38), retired boolean, retire_reason varchar(255), username varchar(50), system_id varchar(50), email varchar(255), person_id int);
create table if not exists user_role_AUD (REV int, user_id int, role varchar(50), REVTYPE tinyint);
create table if not exists role_AUD (role varchar(50), REV int, REVTYPE tinyint, uuid varchar(38), description varchar(255));
create table if not exists role_privilege_AUD (REV int, role varchar(50), privilege varchar(250), REVTYPE tinyint);
create table if not exists location_AUD (location_id int, REV int, REVTYPE tinyint, uuid varchar(38), name varchar(255), description clob, retired boolean, retire_reason varchar(255), parent_location int);
create table if not exists program_AUD (program_id int, REV int, REVTYPE tinyint, uuid varchar(38), name varchar(100), description varchar(500), retired boolean, concept_id int);
create table if not exists visit_AUD (visit_id int, REV int, REVTYPE tinyint, uuid varchar(38), voided boolean, void_reason varchar(255), date_started datetime, date_stopped datetime, location_id int, patient_id int, visit_type_id int);
create table if not exists read_audit_log (id int auto_increment primary key, entity_name varchar(255) not null, read_success boolean not null, username varchar(50), user_uuid varchar(38) not null, event_time datetime not null, ip_address varchar(50), user_agent varchar(500), session_id varchar(100), uuid char(38) not null);
create table if not exists read_audit_entity_metadata (id bigint auto_increment primary key, read_audit_id int not null, entity_uuid varchar(38) not null, patient_uuid varchar(38), patient_name varchar(255));
create table if not exists actcore_raised_flag (patient_id int not null, flag_id int not null, raised_since datetime not null, dismissed_by int, date_dismissed datetime, primary key (patient_id, flag_id));
