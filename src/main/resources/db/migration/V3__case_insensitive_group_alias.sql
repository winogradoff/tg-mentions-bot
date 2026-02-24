drop index if exists idx_chat_group_alias;
create unique index if not exists idx_chat_group_alias_ci on chat_group_alias (chat_id, lower(alias_name));
