create sequence user_id_seq;

create table "user" (
    id bigint primary key, -- user_id_seq에서 먼저 받는다(암호화 AAD에 쓰므로 기본값 없음)
    provider text not null,
    provider_subject text not null,
    login_email bytea not null,
    email_verified boolean not null,
    display_name text,
    constraint user_provider_subject_key unique (provider, provider_subject)
);

-- Spring Session JDBC 기본 PostgreSQL 스키마에서 session_id만 SHA-256 해시 형식으로 제한했다
create table spring_session (
    primary_id char(36) not null,
    session_id char(64) not null,
    creation_time bigint not null,
    last_access_time bigint not null,
    max_inactive_interval int not null,
    expiry_time bigint not null,
    principal_name varchar(100),
    constraint spring_session_pk primary key (primary_id),
    constraint spring_session_session_id_hash check (session_id ~ '^[0-9a-f]{64}$')
);

create unique index spring_session_ix1 on spring_session (session_id);
create index spring_session_ix2 on spring_session (expiry_time);
create index spring_session_ix3 on spring_session (principal_name);

create table spring_session_attributes (
    session_primary_id char(36) not null,
    attribute_name varchar(200) not null,
    attribute_bytes bytea not null,
    constraint spring_session_attributes_pk primary key (session_primary_id, attribute_name),
    constraint spring_session_attributes_fk foreign key (session_primary_id)
        references spring_session (primary_id) on delete cascade
);
