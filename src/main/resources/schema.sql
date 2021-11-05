create table if not exists chat
(
    chat_id            bigint not null primary key,
    chat_title         varchar(300),
    chat_username      varchar(300),
    is_anarchy_enabled bool   not null default false
);

create table if not exists chat_group
(
    group_id bigserial primary key,
    chat_id  bigint not null,
    foreign key (chat_id) references chat (chat_id)
);

create table if not exists chat_group_alias
(
    alias_id   bigserial primary key,
    alias_name varchar(200) not null,
    chat_id    bigint       not null,
    group_id   bigint       not null,
    foreign key (chat_id) references chat (chat_id),
    foreign key (group_id) references chat_group (group_id)
);

create table if not exists member
(
    member_id   bigserial primary key,
    group_id    bigint       not null,
    member_name varchar(200) not null,
    user_id     bigint       null,
    foreign key (group_id) references chat_group (group_id)
);

create unique index if not exists idx_chat_group_alias on chat_group_alias (chat_id, alias_name);
create unique index if not exists idx_member on member (group_id, member_name);
