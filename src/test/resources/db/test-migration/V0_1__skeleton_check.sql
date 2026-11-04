-- 골격 확인용 테이블. 실제 테이블은 각 기능이 src/main/resources/db/migration에 V1부터 추가한다.
create sequence skeleton_check_seq;

create table skeleton_check (
    id   bigint primary key,
    name text   not null
);
