import logging
import os
import textwrap
import urllib.parse as urlparse
from contextlib import contextmanager
from typing import Optional, List

from psycopg2.extras import DictConnection, DictCursor
from psycopg2.pool import ThreadedConnectionPool

import constraints
from models import Chat, GroupAlias, Member

POOL: Optional[ThreadedConnectionPool] = None


def create_pool():
    global POOL
    logging.info("Creating database pool...")
    url = urlparse.urlparse(os.environ.get('DATABASE_URL'))
    POOL = ThreadedConnectionPool(
        minconn=constraints.MIN_DATABASE_POOL_CONNECTIONS,
        maxconn=constraints.MAX_DATABASE_POOL_CONNECTIONS,
        database=url.path[1:],
        user=url.username,
        password=url.password,
        host=url.hostname,
        port=url.port,
        connection_factory=DictConnection,
        cursor_factory=DictCursor
    )
    logging.info("Database pool was created successfully!")


def close_pool():
    if POOL:
        POOL.closeall()


@contextmanager
def get_connection() -> DictConnection:
    logging.info("Getting DB connection...")
    connection = POOL.getconn()
    logging.info("Got DB connection!")
    try:
        yield connection
    finally:
        POOL.putconn(connection)


@contextmanager
def get_cursor(connection, commit=False) -> DictCursor:
    with connection as conn:
        cursor = conn.cursor()
        try:
            yield cursor
            if commit:
                conn.commit()
        finally:
            cursor.close()


def create_schema(connection: DictConnection):
    with get_cursor(connection) as cursor:
        cursor.execute("SELECT version() as version;")
        version = cursor.fetchone()["version"]
        logging.info(f"You are connected to - {version}")

    with get_cursor(connection) as cursor:
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


def select_chat(connection: DictConnection, chat_id: int) -> Optional[Chat]:
    logging.info(f"DB: selecting chat: chat_id=[{chat_id}]")
    with get_cursor(connection) as cursor:
        cursor.execute(
            "select chat_id, is_anarchy_enabled"
            " from chat"
            " where chat_id = %(chat_id)s",
            {"chat_id": chat_id}
        )
        row = cursor.fetchone()
        if not row:
            return None
        return Chat(chat_id=row["chat_id"], is_anarchy_enabled=row["is_anarchy_enabled"])


def select_chat_for_update(connection: DictConnection, chat_id: int):
    logging.info(f"DB: selecting chat for update: chat_id=[{chat_id}]")
    with get_cursor(connection) as cursor:
        logging.info(f"DB: selecting chat for update: chat_id=[{chat_id}]")
        cursor.execute("select 1 from chat where chat_id = %(chat_id)s for update", {"chat_id": chat_id})


def select_group_by_alias_name(connection: DictConnection, chat_id: int, alias_name: str) -> Optional[GroupAlias]:
    logging.info(f"DB: selecting group: chat_id=[{chat_id}], alias_name=[{alias_name}]")
    with get_cursor(connection) as cursor:
        cursor.execute(
            "select chat_id, group_id, alias_id, alias_name"
            " from chat_group_alias"
            " where chat_id = %(chat_id)s and alias_name = %(alias_name)s",
            {"chat_id": chat_id, "alias_name": alias_name}
        )
        row = cursor.fetchone()
        if not row:
            return None
        return GroupAlias(
            chat_id=row["chat_id"],
            group_id=row["group_id"],
            alias_id=row["alias_id"],
            alias_name=row["alias_name"]
        )


def select_members(connection: DictConnection, group_id: int) -> List[Member]:
    logging.info(f"DB: selecting members: group_id=[{group_id}]")
    with get_cursor(connection) as cursor:
        cursor.execute(
            "select member_id, member_name, user_id"
            " from member"
            " where group_id = %(group_id)s",
            {"group_id": group_id}
        )
        return [
            Member(
                member_id=x["member_id"],
                member_name=x["member_name"],
                user_id=x["user_id"]
            )
            for x in cursor.fetchall()
        ]


def insert_chat(connection: DictConnection, chat_id: int):
    logging.info(f"DB: inserting chat: chat_id=[{chat_id}]")
    with get_cursor(connection) as cursor:
        cursor.execute(
            "insert into chat (chat_id)"
            " values (%(chat_id)s) on conflict do nothing",
            {"chat_id": chat_id}
        )


def set_chat_anarchy(connection: DictConnection, chat_id: int, is_anarchy_enabled: bool):
    logging.info(f"DB: setting chat anarchy: chat_id=[{chat_id}], is_anarchy_enabled=[{is_anarchy_enabled}]")
    with get_cursor(connection) as cursor:
        cursor.execute(
            "update chat set is_anarchy_enabled = %(is_anarchy_enabled)s"
            " where chat_id = %(chat_id)s",
            {
                "chat_id": chat_id,
                "is_anarchy_enabled": "true" if is_anarchy_enabled else "false"
            }
        )


def insert_group(connection: DictConnection, chat_id: int) -> int:
    logging.info(f"DB: inserting group: chat_id=[{chat_id}]")
    with get_cursor(connection) as cursor:
        cursor.execute(
            "insert into chat_group (chat_id)"
            " values (%(chat_id)s) on conflict do nothing"
            " returning group_id",
            {"chat_id": chat_id}
        )
        return cursor.fetchone()["group_id"]


def select_group_aliases_by_chat_id(connection: DictConnection, chat_id: int) -> List[GroupAlias]:
    logging.info(f"DB: selecting group aliases: chat_id=[{chat_id}]")
    with get_cursor(connection) as cursor:
        cursor.execute(
            "select chat_id, group_id, alias_id, alias_name"
            " from chat_group_alias"
            " where chat_id = %(chat_id)s",
            {"chat_id": chat_id}
        )
        return [
            GroupAlias(
                chat_id=x["chat_id"],
                group_id=x["group_id"],
                alias_id=x["alias_id"],
                alias_name=x["alias_name"]
            )
            for x in cursor.fetchall()
        ]


def select_group_aliases_by_group_id(connection: DictConnection, group_id: int) -> List[GroupAlias]:
    logging.info(f"DB: selecting group aliases: group_id=[{group_id}]")
    with get_cursor(connection) as cursor:
        cursor.execute(
            "select chat_id, group_id, alias_id, alias_name"
            " from chat_group_alias"
            " where group_id = %(group_id)s",
            {"group_id": group_id}
        )
        return [
            GroupAlias(
                chat_id=x["chat_id"],
                group_id=x["group_id"],
                alias_id=x["alias_id"],
                alias_name=x["alias_name"]
            )
            for x in cursor.fetchall()
        ]


def insert_group_alias(connection: DictConnection, chat_id: int, group_id: int, alias_name: str):
    logging.info(f"DB: inserting group alias: group_id=[{group_id}], alias_name=[{alias_name}]")
    with get_cursor(connection) as cursor:
        cursor.execute(
            "insert into chat_group_alias (chat_id, group_id, alias_name)"
            " values (%(chat_id)s, %(group_id)s, %(alias_name)s) on conflict do nothing",
            {"chat_id": chat_id, "group_id": group_id, "alias_name": alias_name}
        )


def delete_group_alias(connection: DictConnection, alias_id: int):
    logging.info(f"DB: deleting group alias: alias_id=[{alias_id}]")
    with get_cursor(connection) as cursor:
        cursor.execute("delete from chat_group_alias where alias_id = %(alias_id)s", {"alias_id": alias_id})


def delete_group(connection: DictConnection, group_id: int):
    logging.info(f"DB: deleting group: group_id=[{group_id}]")
    with get_cursor(connection) as cursor:
        cursor.execute("delete from chat_group where group_id = %(group_id)s", {"group_id": group_id})


def insert_member(connection: DictConnection, group_id: int, member: Member):
    logging.info(f"DB: inserting member: group_id=[{group_id}], member=[{member}]")
    with get_cursor(connection) as cursor:
        cursor.execute(
            "insert into member (group_id, member_name, user_id)"
            " values (%(group_id)s, %(member_name)s, %(user_id)s) on conflict do nothing",
            {"group_id": group_id, "member_name": member.member_name, "user_id": member.user_id}
        )


def delete_member(connection: DictConnection, group_id: int, member_name: str):
    logging.info(f"DB: deleting member: group_id=[{group_id}], member_name=[{member_name}]")
    with get_cursor(connection) as cursor:
        cursor.execute(
            "delete from member where group_id = %(group_id)s and member_name = %(member_name)s",
            {"group_id": group_id, "member_name": member_name}
        )
