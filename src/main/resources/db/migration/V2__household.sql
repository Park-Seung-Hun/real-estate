create sequence household_id_seq;
create sequence invitation_id_seq;

create table household (
    id bigint primary key
);

-- 사용자 한 명은 가구 하나에만 속한다(user_id 유니크)
create table household_member (
    household_id bigint not null references household (id),
    user_id bigint not null references "user" (id),
    role text not null,
    joined_at timestamptz not null,
    constraint household_member_pk primary key (household_id, user_id),
    constraint household_member_user_id_key unique (user_id),
    constraint household_member_role_check check (role in ('owner', 'member'))
);

-- token_hash에는 토큰 원문이 아니라 SHA-256 32바이트만 저장한다
create table invitation (
    id bigint primary key,
    household_id bigint not null references household (id),
    created_by bigint not null references "user" (id),
    token_hash bytea not null,
    status text not null,
    expires_at timestamptz not null,
    created_at timestamptz not null,
    constraint invitation_token_hash_key unique (token_hash),
    constraint invitation_token_hash_length check (octet_length(token_hash) = 32),
    constraint invitation_status_check check (status in ('대기', '수락', '취소')),
    constraint invitation_expiry_after_creation check (expires_at > created_at)
);
