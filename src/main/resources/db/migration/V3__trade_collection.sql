create sequence complex_id_seq;
create sequence trade_id_seq;
create sequence trade_history_id_seq;

create table complex (
    id bigint primary key,
    apt_seq text not null,
    name text not null,
    sgg_cd text not null,
    umd_nm text not null,
    jibun text,
    constraint complex_apt_seq_key unique (apt_seq),
    constraint complex_sgg_cd_format check (sgg_cd ~ '^[0-9]{5}$')
);

create index complex_sgg_cd_idx on complex (sgg_cd);

-- 거래 행은 삭제하지 않는다. 이력 행과 발송 기록이 가리키므로 연쇄 삭제도 두지 않는다
create table trade (
    id bigint primary key,
    complex_id bigint not null references complex (id),
    deal_date date not null,
    floor integer not null,
    exclusive_area numeric not null,
    amount bigint not null,
    canceled boolean not null,
    same_key_count integer not null,
    cancel_date date,
    registry_date date,
    apt_dong text,
    first_seen_at timestamptz not null,
    constraint trade_same_key_count_check check (same_key_count >= 0),
    -- numeric 비교는 자릿수를 보지 않아 84.9와 84.90을 같은 키로 본다
    constraint trade_identity_key unique (complex_id, deal_date, floor, exclusive_area, amount, canceled)
);

create table trade_history (
    id bigint primary key,
    trade_id bigint not null references trade (id),
    change_type text not null,
    observed_at timestamptz not null,
    constraint trade_history_change_type_check check (change_type in ('신규', '해제', '등기', '사라짐'))
);

create table fetch_range (
    lawd_cd text not null,
    deal_ymd text not null,
    status text not null,
    -- 마지막으로 성공한 시각. 실패해도 비우지 않는다
    fetched_at timestamptz,
    failure_reason text,
    constraint fetch_range_pk primary key (lawd_cd, deal_ymd),
    constraint fetch_range_lawd_cd_format check (lawd_cd ~ '^[0-9]{5}$'),
    constraint fetch_range_deal_ymd_format check (deal_ymd ~ '^[0-9]{4}(0[1-9]|1[0-2])$'),
    constraint fetch_range_status_check check (status in ('성공', '실패'))
);
