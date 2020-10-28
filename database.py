import logging
import os
import textwrap
from typing import Optional, List

import psycopg2

from models import Chat, GroupAlias, Member


class Database:
    CONNECTION: None

    def __init__(self):
        self.database_url = os.environ['DATABASE_URL']
        self.is_debug = os.getenv('DEBUG') is not None

    def connect(self):
        # todo: connection pool
        logging.info("Connection to DB...")
        if self.is_debug:
            # local db without ssl
            self.CONNECTION = psycopg2.connect(self.database_url)
        else:
            self.CONNECTION = psycopg2.connect(self.database_url, sslmode='require')
        logging.info("Successful database connection!")

    def disconnect(self):
        logging.info("Disconnection from DB...")
        self.CONNECTION.close()
        logging.info("Successfully disconnected from DB!")

    def get_connection(self):
        return self.CONNECTION

    def create_schema(self):
        with self.CONNECTION as conn:
            with conn.cursor() as cursor:
                cursor.execute("SELECT version();")
                record = cursor.fetchone()
                logging.info(f"You are connected to - {record}")

        with self.CONNECTION as conn:
            with conn.cursor() as cursor:
                logging.info("Database schema creation...")
                cursor.execute(textwrap.dedent(
                    """
                        create table if not exists chat
                        (
                            chat_id            bigint not null primary key,
                            is_anarchy_enabled bool not null default false
                        );
    
                        create table if not exists chat_group
                        (
                            group_id    bigserial primary key,
                            chat_id     bigint       not null,
                            foreign key (chat_id) references chat (chat_id)
                        );
    
                        create table if not exists chat_group_alias
                        (
                            alias_id    bigserial primary key,
                            alias_name  varchar(200) not null,
                            chat_id     bigint       not null,
                            group_id    bigint       not null,
                            foreign key (chat_id) references chat (chat_id),
                            foreign key (group_id) references chat_group (group_id)
                        );
    
                        create table if not exists member
                        (
                            member_id   bigserial primary key,
                            group_id    bigint       not null,
                            member_name varchar(200) not null,
                            user_id     bigint           null,
                            foreign key (group_id) references chat_group (group_id)
                        );
    
                        create unique index if not exists idx_chat_group_alias on chat_group_alias (chat_id, alias_name);
                        create unique index if not exists idx_member on member (group_id, member_name);
                    """
                ))
                logging.info("Database schema was created successfully!")

    def select_chat(self, chat_id: int) -> Optional[Chat]:
        with self.CONNECTION as conn:
            with conn.cursor() as cursor:
                cursor.execute(
                    "select chat_id, is_anarchy_enabled"
                    " from chat"
                    " where chat_id = %s",
                    (chat_id,)
                )
                row = cursor.fetchone()
                if not row:
                    return None
                return Chat(chat_id=row[0], is_anarchy_enabled=row[1])

    def select_chat_for_update(self, chat_id: int):
        logging.info(f"DB: selecting chat for update: chat_id=[{chat_id}]")
        with self.CONNECTION as conn:
            with conn.cursor() as cursor:
                cursor.execute(
                    "select 1 from chat where chat_id = %s for update",
                    (chat_id,)
                )

    def get_group_by_alias_name(self, chat_id: int, alias_name: str) -> Optional[GroupAlias]:
        with self.CONNECTION as conn:
            with conn.cursor() as cursor:
                cursor.execute(
                    "select chat_id, group_id, alias_id, alias_name"
                    " from chat_group_alias"
                    " where chat_id = %s and alias_name = %s",
                    (chat_id, alias_name,)
                )
                row = cursor.fetchone()
                if not row:
                    return None
                return GroupAlias(
                    chat_id=row[0],
                    group_id=row[1],
                    alias_id=row[2],
                    alias_name=row[3]
                )

    def select_members(self, group_id: int) -> List[Member]:
        with self.CONNECTION as conn:
            with conn.cursor() as cursor:
                cursor.execute(
                    "select member_id, member_name, user_id"
                    " from member"
                    " where group_id = %s",
                    (group_id,)
                )
                return [
                    Member(member_id=x[0], member_name=x[1], user_id=x[2])
                    for x in cursor.fetchall()
                ]

    def insert_chat(self, chat_id: int):
        logging.info(f"DB: inserting chat: chat_id=[{chat_id}]")
        with self.CONNECTION as conn:
            with conn.cursor() as cursor:
                cursor.execute(
                    "insert into chat (chat_id)"
                    " values (%s) on conflict do nothing",
                    (chat_id,)
                )

    def set_chat_anarchy(self, chat_id: int, is_anarchy_enabled: bool):
        logging.info(f"DB: inserting chat: chat_id=[{chat_id}]")
        with self.CONNECTION as conn:
            with conn.cursor() as cursor:
                cursor.execute(
                    "update chat set is_anarchy_enabled = %s"
                    " where chat_id = %s",
                    ("true" if is_anarchy_enabled else "false", chat_id,)
                )

    def insert_group(self, chat_id: int) -> int:
        logging.info(f"DB: inserting group: chat_id=[{chat_id}]")
        with self.CONNECTION as conn:
            with conn.cursor() as cursor:
                cursor.execute(
                    "insert into chat_group (chat_id)"
                    " values (%s) on conflict do nothing"
                    " returning group_id",
                    (chat_id,)
                )
                return cursor.fetchone()[0]

    def select_group_aliases_by_chat_id(self, chat_id: int) -> List[GroupAlias]:
        with self.CONNECTION as conn:
            with conn.cursor() as cursor:
                cursor.execute(
                    "select chat_id, group_id, alias_id, alias_name"
                    " from chat_group_alias"
                    " where chat_id = %s",
                    (chat_id,)
                )
                return [
                    GroupAlias(chat_id=x[0], group_id=x[1], alias_id=x[2], alias_name=x[3])
                    for x in cursor.fetchall()
                ]

    def select_group_aliases_by_group_id(self, group_id: int) -> List[GroupAlias]:
        with self.CONNECTION as conn:
            with conn.cursor() as cursor:
                cursor.execute(
                    "select chat_id, group_id, alias_id, alias_name"
                    " from chat_group_alias"
                    " where group_id = %s",
                    (group_id,)
                )
                return [
                    GroupAlias(chat_id=x[0], group_id=x[1], alias_id=x[2], alias_name=x[3])
                    for x in cursor.fetchall()
                ]

    def insert_group_alias(self, chat_id: int, group_id: int, alias_name: str):
        logging.info(f"DB: inserting group alias: group_id=[{group_id}], alias_name=[{alias_name}]")
        with self.CONNECTION as conn:
            with conn.cursor() as cursor:
                cursor.execute(
                    "insert into chat_group_alias (chat_id, group_id, alias_name)"
                    " values (%s, %s, %s) on conflict do nothing",
                    (chat_id, group_id, alias_name,)
                )

    def delete_group_alias(self, alias_id: int):
        logging.info(f"DB: deleting group alias: alias_id=[{alias_id}]")
        with self.CONNECTION as conn:
            with conn.cursor() as cursor:
                cursor.execute("delete from chat_group_alias where alias_id = %s", (alias_id,))

    def delete_group(self, group_id: int):
        logging.info(f"DB: deleting group: group_id=[{group_id}]")
        with self.CONNECTION as conn:
            with conn.cursor() as cursor:
                cursor.execute("delete from chat_group where group_id = %s", (group_id,))

    def insert_member(self, group_id: int, member: Member):
        logging.info(f"DB: inserting member: group_id=[{group_id}], member=[{member}]")
        with self.CONNECTION as conn:
            with conn.cursor() as cursor:
                cursor.execute(
                    "insert into member (group_id, member_name, user_id)"
                    " values (%s, %s, %s) on conflict do nothing",
                    (group_id, member.member_name, member.user_id,)
                )

    def delete_member(self, group_id: int, member_name: str):
        logging.info(f"DB: deleting member: group_id=[{group_id}], member_name=[{member_name}]")
        with self.CONNECTION as conn:
            with conn.cursor() as cursor:
                cursor.execute(
                    "delete from member where group_id = %s and member_name = %s",
                    (group_id, member_name,)
                )
