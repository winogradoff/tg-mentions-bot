import logging
import os
import re
import textwrap
from dataclasses import dataclass
from enum import Enum, auto
from typing import Optional, List, Dict

import aiogram.types as types
import psycopg2
from aiogram import Bot, Dispatcher
from aiogram.contrib.fsm_storage.memory import MemoryStorage
from aiogram.contrib.middlewares.logging import LoggingMiddleware
from aiogram.types import ParseMode, MessageEntityType, ChatMember, ChatType
from aiogram.utils import markdown, executor
from aiogram.utils.callback_data import CallbackData
from aiogram.utils.exceptions import MessageNotModified
from aiogram.utils.text_decorations import markdown_decoration

logging.basicConfig(
    format=u'%(filename)+13s [ LINE:%(lineno)-4s] %(levelname)-8s [%(asctime)s] %(message)s',
    level=logging.DEBUG
)


class Grant(Enum):
    READ_ACCESS = auto()
    WRITE_ACCESS = auto()
    CHANGE_CHAT_SETTINGS = auto()


@dataclass
class Chat:
    chat_id: int
    is_anarchy_enabled: bool


@dataclass
class Group:
    group_id: int


@dataclass
class GroupAlias:
    chat_id: int
    group_id: int
    alias_name: str
    alias_id: Optional[int] = None


@dataclass
class Member:
    member_name: str
    member_id: Optional[int] = None
    user_id: Optional[int] = None


class AuthorizationError(RuntimeError):
    pass


class IllegalStateError(RuntimeError):
    pass


bot = Bot(token=os.getenv("TOKEN"))
dp = Dispatcher(bot=bot, storage=MemoryStorage())
dp.middleware.setup(LoggingMiddleware())

group_cd = CallbackData('group', 'key', 'action')  # group:<id>:<action>

MAX_GROUP_NAME_LENGTH = 10
MAX_ALIASES_PER_GROUP = 3

REGEX_CMD = r"(?:[@a-zA-Z0-9]|[-_])+"
REGEX_GROUP = r"(?:[a-zA-Z0-9]|[а-яА-ЯёЁ]|[-_])+"
REGEX_MEMBER = r"(?:[@\w]|[-])+"

REGEX_CMD_GROUP = re.compile(fr"^/({REGEX_CMD})\s+(?P<group>{REGEX_GROUP})$")
REGEX_CMD_GROUP_RENAME = re.compile(fr"^/({REGEX_CMD})\s+(?P<group>{REGEX_GROUP})\s+(?P<new_group>{REGEX_GROUP})$")

REGEX_CMD_GROUP_MESSAGE = re.compile(fr'^/({REGEX_CMD})\s+(?P<group>{REGEX_GROUP})(\s+(.|\n)*)*')
REGEX_CMD_GROUP_ALIAS = re.compile(fr"^/({REGEX_CMD})\s+(?P<group>{REGEX_GROUP})\s+(?P<alias>{REGEX_GROUP})$")
REGEX_CMD_GROUP_MEMBERS = re.compile(fr'^/({REGEX_CMD})\s+(?P<group>{REGEX_GROUP})(\s+(?P<member>{REGEX_MEMBER}))+$')

DB_CONNECTION = None


def db_connect():
    database_url = os.environ['DATABASE_URL']
    # todo: connection pool
    logging.info("Connection to DB...")
    if os.getenv('DEBUG') is not None:
        # local db without ssl
        connection = psycopg2.connect(database_url)
    else:
        connection = psycopg2.connect(database_url, sslmode='require')
    logging.info("Successful database connection!")
    return connection


def db_create_schema():
    with DB_CONNECTION as conn:
        with conn.cursor() as cursor:
            cursor.execute("SELECT version();")
            record = cursor.fetchone()
            logging.info(f"You are connected to - {record}")

    with DB_CONNECTION as conn:
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


def db_select_chat(chat_id: int) -> Optional[Chat]:
    with DB_CONNECTION as conn:
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


def db_select_chat_for_update(chat_id: int):
    logging.info(f"DB: selecting chat for update: chat_id=[{chat_id}]")
    with DB_CONNECTION as conn:
        with conn.cursor() as cursor:
            cursor.execute(
                "select 1 from chat where chat_id = %s for update",
                (chat_id,)
            )


def db_get_group_by_alias_name(chat_id: int, alias_name: str) -> Optional[GroupAlias]:
    with DB_CONNECTION as conn:
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


def db_select_members(group_id: int) -> List[Member]:
    with DB_CONNECTION as conn:
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


def db_insert_chat(chat_id: int):
    logging.info(f"DB: inserting chat: chat_id=[{chat_id}]")
    with DB_CONNECTION as conn:
        with conn.cursor() as cursor:
            cursor.execute(
                "insert into chat (chat_id)"
                " values (%s) on conflict do nothing",
                (chat_id,)
            )


def db_set_chat_anarchy(chat_id: int, is_anarchy_enabled: bool):
    logging.info(f"DB: inserting chat: chat_id=[{chat_id}]")
    with DB_CONNECTION as conn:
        with conn.cursor() as cursor:
            cursor.execute(
                "update chat set is_anarchy_enabled = %s"
                " where chat_id = %s",
                ("true" if is_anarchy_enabled else "false", chat_id,)
            )


def db_insert_group(chat_id: int) -> int:
    logging.info(f"DB: inserting group: chat_id=[{chat_id}]")
    with DB_CONNECTION as conn:
        with conn.cursor() as cursor:
            cursor.execute(
                "insert into chat_group (chat_id)"
                " values (%s) on conflict do nothing"
                " returning group_id",
                (chat_id,)
            )
            return cursor.fetchone()[0]


def db_select_group_aliases_by_chat_id(chat_id: int) -> List[GroupAlias]:
    with DB_CONNECTION as conn:
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


def db_select_group_aliases_by_group_id(group_id: int) -> List[GroupAlias]:
    with DB_CONNECTION as conn:
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


def db_insert_group_alias(chat_id: int, group_id: int, alias_name: str):
    logging.info(f"DB: inserting group alias: group_id=[{group_id}], alias_name=[{alias_name}]")
    with DB_CONNECTION as conn:
        with conn.cursor() as cursor:
            cursor.execute(
                "insert into chat_group_alias (chat_id, group_id, alias_name)"
                " values (%s, %s, %s) on conflict do nothing",
                (chat_id, group_id, alias_name,)
            )


def db_delete_group_alias(alias_id: int):
    logging.info(f"DB: deleting group alias: alias_id=[{alias_id}]")
    with DB_CONNECTION as conn:
        with conn.cursor() as cursor:
            cursor.execute("delete from chat_group_alias where alias_id = %s", (alias_id,))


def db_delete_group(group_id: int):
    logging.info(f"DB: deleting group: group_id=[{group_id}]")
    with DB_CONNECTION as conn:
        with conn.cursor() as cursor:
            cursor.execute("delete from chat_group where group_id = %s", (group_id,))


def db_insert_member(group_id: int, member: Member):
    logging.info(f"DB: inserting member: group_id=[{group_id}], member=[{member}]")
    with DB_CONNECTION as conn:
        with conn.cursor() as cursor:
            cursor.execute(
                "insert into member (group_id, member_name, user_id)"
                " values (%s, %s, %s) on conflict do nothing",
                (group_id, member.member_name, member.user_id,)
            )


def db_delete_member(group_id: int, member_name: str):
    logging.info(f"DB: deleting member: group_id=[{group_id}], member_name=[{member_name}]")
    with DB_CONNECTION as conn:
        with conn.cursor() as cursor:
            cursor.execute(
                "delete from member where group_id = %s and member_name = %s",
                (group_id, member_name,)
            )


@dp.message_handler(commands=['start', 'help'])
async def handler_help(message: types.Message):
    await check_access(message, grant=Grant.READ_ACCESS)
    await message.reply(
        text=markdown.text(
            f"Привет, {message.from_user.get_mention()}! 👋",
            "",
            markdown_decoration.bold('Я поддерживаю команды:'),
            markdown.escape_md('/list_groups — просмотр списка групп'),
            markdown.escape_md('/add_group — добавление группы'),
            markdown.escape_md('/remove_group — удаление группы'),
            markdown.escape_md('/add_group_alias — добавление алиаса группы'),
            markdown.escape_md('/remove_group_alias — удаление алиаса группы'),
            markdown.escape_md('/list_members — список пользователей в группе'),
            markdown.escape_md('/add_members — добавление пользователей в группу'),
            markdown.escape_md('/remove_members — удаление пользователей из группы'),
            markdown.escape_md('/enable_anarchy — включить анархию'),
            markdown.escape_md('/disable_anarchy — выключить анархию'),
            markdown.escape_md('/call — позвать пользователей группы'),
            markdown.escape_md('/help — справка по всем операциям'),
            sep='\n'
        ),
        parse_mode=ParseMode.MARKDOWN
    )


@dp.message_handler(commands=['list_groups'])
async def handler_list_groups(message: types.Message):
    await check_access(message, grant=Grant.READ_ACCESS)

    aliases: List[GroupAlias] = db_select_group_aliases_by_chat_id(chat_id=message.chat.id)
    if len(aliases) == 0:
        return await message.reply("Нет ни одной группы.", parse_mode=ParseMode.MARKDOWN)

    aliases_lookup: Dict[int, List[GroupAlias]] = {}
    for a in db_select_group_aliases_by_chat_id(chat_id=message.chat.id):
        aliases_lookup.setdefault(a.group_id, []).append(a)

    groups_for_print = []
    for group_id in sorted({x.group_id for x in aliases}):
        group_aliases = sorted(aliases_lookup.get(group_id, []), key=lambda x: x.alias_id)
        group_aliases = [x.alias_name for x in group_aliases]
        head, *tail = group_aliases
        tail = f" (синонимы: {', '.join(tail)})" if len(tail) > 0 else ""
        groups_for_print.append(f"- {head}{tail}")

    await message.reply(
        markdown.text(
            markdown_decoration.bold("Вот такие группы существуют:"),
            markdown_decoration.code("\n".join(groups_for_print)),
            sep='\n'
        ),
        parse_mode=ParseMode.MARKDOWN
    )


@dp.message_handler(commands=['add_group'])
async def handler_add_group(message: types.Message):
    await check_access(message, grant=Grant.WRITE_ACCESS)
    match = REGEX_CMD_GROUP.search(message.text)
    if not match:
        return await message.reply(
            markdown.text(
                markdown_decoration.bold("Пример вызова:"),
                markdown_decoration.code("/add_group group"),
                " ",
                markdown.text("group:", markdown_decoration.code(REGEX_GROUP)),
                sep='\n'
            ),
            parse_mode=ParseMode.MARKDOWN
        )
    group_name = match.group("group")

    if len(group_name) > MAX_GROUP_NAME_LENGTH:
        return await message.reply('Слишком длинное название группы!')

    with DB_CONNECTION:
        db_insert_chat(chat_id=message.chat.id)
        db_select_chat_for_update(chat_id=message.chat.id)

        group = db_get_group_by_alias_name(chat_id=message.chat.id, alias_name=group_name)
        if group:
            return await message.reply('Такая группа уже существует!')

        group_id = db_insert_group(chat_id=message.chat.id)
        db_insert_group_alias(
            chat_id=message.chat.id,
            group_id=group_id,
            alias_name=group_name
        )

    await message.reply(
        markdown.text("Группа", markdown_decoration.code(group_name), "добавлена!"),
        parse_mode=ParseMode.MARKDOWN
    )


@dp.message_handler(commands=['remove_group'])
async def handler_remove_group(message: types.Message):
    await check_access(message, Grant.WRITE_ACCESS)
    match = REGEX_CMD_GROUP.search(message.text)
    if not match:
        return await message.reply(
            markdown.text(
                markdown_decoration.bold("Пример вызова:"),
                markdown_decoration.code("/remove_group group"),
                " ",
                markdown.text("group:", markdown_decoration.code(REGEX_GROUP)),
                sep='\n'
            ),
            parse_mode=ParseMode.MARKDOWN
        )
    group_name = match.group("group")

    with DB_CONNECTION:
        db_select_chat_for_update(chat_id=message.chat.id)
        group = db_get_group_by_alias_name(chat_id=message.chat.id, alias_name=group_name)
        if not group:
            return await message.reply(
                markdown.text('Группа', markdown_decoration.code(group_name), 'не найдена!'),
                parse_mode=ParseMode.MARKDOWN
            )
        logging.info(f"group: {group}")
        members = db_select_members(group.group_id)
        if len(members) != 0:
            logging.info(f"members: {members}")
            return await message.reply('Группу нельзя удалить, в ней есть пользователи!')

        group_aliases = db_select_group_aliases_by_group_id(group_id=group.group_id)

        for a in group_aliases:
            db_delete_group_alias(alias_id=a.alias_id)

        db_delete_group(group_id=group.group_id)

    await message.reply(
        markdown.text("Группа", markdown_decoration.bold(group_name), "удалена!"),
        parse_mode=ParseMode.MARKDOWN
    )


@dp.message_handler(commands=['add_group_alias'])
async def handler_add_group_alias(message: types.Message):
    await check_access(message, Grant.WRITE_ACCESS)
    match = REGEX_CMD_GROUP_ALIAS.search(message.text)
    if not match:
        return await message.reply(
            markdown.text(
                markdown_decoration.bold("Пример вызова:"),
                markdown_decoration.code("/add_group_aliases group alias"),
                " ",
                markdown.text("group:", markdown_decoration.code(REGEX_GROUP)),
                markdown.text("alias:", markdown_decoration.code(REGEX_GROUP)),
                sep='\n'
            ),
            parse_mode=ParseMode.MARKDOWN
        )
    group_name = match.group('group')
    group_alias = match.group('alias')

    with DB_CONNECTION:
        db_select_chat_for_update(chat_id=message.chat.id)
        group = db_get_group_by_alias_name(chat_id=message.chat.id, alias_name=group_name)
        if not group:
            return await message.reply(
                markdown.text('Группа', markdown_decoration.code(group_name), 'не найдена!'),
                parse_mode=ParseMode.MARKDOWN
            )
        logging.info(f"group: {group}")

        aliases: List[GroupAlias] = db_select_group_aliases_by_chat_id(chat_id=message.chat.id)

        if group_alias in set(x.alias_name for x in aliases):
            return await message.reply("Такой алиас уже используется!")

        if len([x for x in aliases if x.group_id == group.group_id]) >= MAX_ALIASES_PER_GROUP:
            return await message.reply("Нельзя добавить так много алиасов!")

        db_insert_group_alias(
            chat_id=message.chat.id,
            group_id=group.group_id,
            alias_name=group_alias
        )

    await message.reply(
        markdown.text(
            "Для группы", markdown_decoration.code(group_name),
            "добавлен алиас", markdown_decoration.code(group_alias)
        ),
        parse_mode=ParseMode.MARKDOWN
    )


@dp.message_handler(commands=['remove_group_alias'])
async def handler_remove_group_alias(message: types.Message):
    await check_access(message, Grant.WRITE_ACCESS)
    match = REGEX_CMD_GROUP_ALIAS.search(message.text)
    if not match:
        return await message.reply(
            markdown.text(
                markdown_decoration.bold("Пример вызова:"),
                markdown_decoration.code("/remove_group_alias group alias"),
                " ",
                markdown.text("group:", markdown_decoration.code(REGEX_GROUP)),
                markdown.text("alias:", markdown_decoration.code(REGEX_GROUP)),
                sep='\n'
            ),
            parse_mode=ParseMode.MARKDOWN
        )
    group_name = match.group('group')
    alias_name = match.group('alias')

    with DB_CONNECTION:
        db_select_chat_for_update(chat_id=message.chat.id)
        group = db_get_group_by_alias_name(chat_id=message.chat.id, alias_name=group_name)
        if not group:
            return await message.reply(
                markdown.text('Группа', markdown_decoration.code(group_name), 'не найдена!'),
                parse_mode=ParseMode.MARKDOWN
            )
        logging.info(f"group: {group}")

        group_aliases: Dict[str, GroupAlias] = {
            x.alias_name: x
            for x in db_select_group_aliases_by_group_id(group_id=group.group_id)
        }

        if alias_name not in group_aliases:
            return await message.reply(
                markdown.text(
                    'Алиас', markdown_decoration.code(alias_name),
                    'не найден для группы', markdown_decoration.code(group_name)
                ),
                parse_mode=ParseMode.MARKDOWN
            )
        group_alias = group_aliases[alias_name]

        if len(group_aliases) == 1:
            return await message.reply(
                markdown.text("Нельзя удалить единственное название группы!"),
                parse_mode=ParseMode.MARKDOWN
            )

        db_delete_group_alias(alias_id=group_alias.alias_id)

    await message.reply(
        markdown.text(
            "Алиас", markdown_decoration.code(alias_name),
            "удалён из группы", markdown_decoration.code(group_name)
        ),
        parse_mode=ParseMode.MARKDOWN
    )


@dp.message_handler(commands=['list_members'])
async def handler_list_members(message: types.Message):
    await check_access(message, grant=Grant.READ_ACCESS)
    match = REGEX_CMD_GROUP.search(message.text)
    if not match:
        return await message.reply(
            markdown.text(
                markdown_decoration.bold("Пример вызова:"),
                markdown_decoration.code("/list_members group"),
                " ",
                markdown.text("group:", markdown_decoration.code(REGEX_GROUP)),
                sep='\n'
            ),
            parse_mode=ParseMode.MARKDOWN
        )
    group_name = match.group("group")
    group = db_get_group_by_alias_name(chat_id=message.chat.id, alias_name=group_name)
    if not group:
        return await message.reply(
            markdown.text('Группа', markdown_decoration.code(group_name), 'не найдена!'),
            parse_mode=ParseMode.MARKDOWN
        )

    members = db_select_members(group_id=group.group_id)
    members = sorted(convert_members_to_names(members))
    logging.info(f"members: {members}")

    if len(members) == 0:
        text = markdown.text(
            "В группе",
            markdown_decoration.code(group_name),
            "нет ни одного пользователя!",
        )
    else:
        text = markdown.text(
            markdown.text(
                markdown_decoration.bold("Участники группы"),
                markdown_decoration.code(group_name)
            ),
            markdown_decoration.code("\n".join([f"- {x}" for x in members])),
            sep='\n'
        )

    await message.reply(text, parse_mode=ParseMode.MARKDOWN)


@dp.message_handler(commands=['add_members', 'add_member'])
async def handler_add_members(message: types.Message):
    await check_access(message, Grant.WRITE_ACCESS)
    match = REGEX_CMD_GROUP_MEMBERS.search(message.text)
    if not match:
        return await message.reply(
            markdown.text(
                markdown_decoration.bold("Пример вызова:"),
                markdown_decoration.code("/add_members group username1 username2"),
                " ",
                markdown.text("group:", markdown_decoration.code(REGEX_GROUP)),
                markdown.text("username:", markdown_decoration.code(REGEX_MEMBER)),
                sep='\n'
            ),
            parse_mode=ParseMode.MARKDOWN
        )
    group_name = match.group('group')

    mentions = [
        Member(member_name=x.get_text(message.text))
        for x in message.entities
        if x.type == MessageEntityType.MENTION
    ]

    text_mentions = [
        Member(
            member_name=x.user.full_name,
            user_id=x.user.id
        )
        for x in message.entities
        if x.type == MessageEntityType.TEXT_MENTION
    ]

    all_members = mentions + text_mentions
    logging.info(f"members: {all_members}")

    if len(all_members) < 1:
        return await message.reply('Нужно указать хотя бы одного пользователя!')

    with DB_CONNECTION:
        db_select_chat_for_update(chat_id=message.chat.id)

        group = db_get_group_by_alias_name(chat_id=message.chat.id, alias_name=group_name)
        if not group:
            return await message.reply(
                markdown.text('Группа', markdown_decoration.code(group_name), 'не найдена!'),
                parse_mode=ParseMode.MARKDOWN
            )
        logging.info(f"group: {group}")

        for member in all_members:
            db_insert_member(group_id=group.group_id, member=member)

    await message.reply(
        markdown.text(
            markdown.text(
                "Пользователи добавленные в группу",
                markdown_decoration.code(group_name),
            ),
            markdown_decoration.code("\n".join([
                f"- {x}" for x in convert_members_to_names(all_members)
            ])),
            sep='\n'
        ),
        parse_mode=ParseMode.MARKDOWN
    )


@dp.message_handler(commands=['remove_members', 'remove_member'])
async def handler_remove_members(message: types.Message):
    await check_access(message, Grant.WRITE_ACCESS)
    match = REGEX_CMD_GROUP_MEMBERS.search(message.text)
    if not match:
        return await message.reply(
            markdown.text(
                markdown_decoration.bold("Пример вызова:"),
                markdown_decoration.code("/remove_members group username1 username2"),
                " ",
                markdown.text("group:", markdown_decoration.code(REGEX_GROUP)),
                markdown.text("username:", markdown_decoration.code(REGEX_MEMBER)),
                sep='\n'
            ),
            parse_mode=ParseMode.MARKDOWN
        )
    group_name = match.group('group')
    group = db_get_group_by_alias_name(chat_id=message.chat.id, alias_name=group_name)
    if not group:
        return await message.reply(
            markdown.text('Группа', markdown_decoration.code(group_name), 'не найдена!'),
            parse_mode=ParseMode.MARKDOWN
        )
    logging.info(f"group: {group}")

    mentions = [
        x.get_text(message.text)
        for x in message.entities
        if x.type == MessageEntityType.MENTION
    ]
    text_mentions = [
        f"[{x.user.full_name}]({x.user.url})"
        for x in message.entities
        if x.type == MessageEntityType.TEXT_MENTION
    ]
    all_members = mentions + text_mentions
    logging.info(f"members: {all_members}")

    if len(all_members) < 1:
        return await message.reply('Нужно указать хотя бы одного пользователя!')

    with DB_CONNECTION:
        for member in all_members:
            try:
                db_delete_member(group_id=group.group_id, member_name=member)
            except (Exception, psycopg2.Error) as error:
                logging.error("Error for delete operation", error)
                return await message.reply('Возникла ошибка при удалении пользователей!')

    await message.reply(
        markdown.text(
            markdown.text(
                "Пользователи удалённые из группы",
                markdown_decoration.code(group_name)
            ),
            markdown_decoration.code("\n".join([f"- {x}" for x in all_members])),
            sep='\n'
        ),
        parse_mode=ParseMode.MARKDOWN
    )


@dp.message_handler(commands=['call'])
async def handler_call(message: types.Message):
    await check_access(message, grant=Grant.READ_ACCESS)
    match = REGEX_CMD_GROUP_MESSAGE.search(message.text)
    if not match:
        return await message.reply(
            markdown.text(
                markdown_decoration.bold("Пример вызова:"),
                markdown_decoration.code("/call group"),
                " ",
                markdown.text("group:", markdown_decoration.code(REGEX_GROUP)),
                sep='\n'
            ),
            parse_mode=ParseMode.MARKDOWN
        )
    group_name = match.group("group")
    group = db_get_group_by_alias_name(chat_id=message.chat.id, alias_name=group_name)
    if not group:
        return await message.reply(
            markdown.text('Группа', markdown_decoration.code(group_name), 'не найдена!'),
            parse_mode=ParseMode.MARKDOWN
        )
    logging.info(f"group: {group}")

    members = db_select_members(group_id=group.group_id)
    if len(members) == 0:
        return await message.reply('Группа пользователей пуста!')

    mentions = convert_members_to_mentions(members)

    text = " ".join(mentions)
    if message.reply_to_message:
        await message.reply_to_message.reply(text, parse_mode=ParseMode.MARKDOWN)
    else:
        await message.reply(text, parse_mode=ParseMode.MARKDOWN)


@dp.message_handler(commands=['enable_anarchy'])
async def handler_enable_anarchy(message: types.Message):
    await check_access(message, Grant.CHANGE_CHAT_SETTINGS)
    db_insert_chat(chat_id=message.chat.id)
    db_set_chat_anarchy(chat_id=message.chat.id, is_anarchy_enabled=True)
    await message.reply("Анархия включена")


@dp.message_handler(commands=['disable_anarchy'])
async def handler_disable_anarchy(message: types.Message):
    await check_access(message, Grant.CHANGE_CHAT_SETTINGS)
    db_insert_chat(chat_id=message.chat.id)
    db_set_chat_anarchy(chat_id=message.chat.id, is_anarchy_enabled=False)
    await message.reply("Анархия выключена")


@dp.errors_handler()
async def handler_error(update, error):
    if isinstance(error, MessageNotModified):
        return True
    elif isinstance(error, AuthorizationError):
        await update.message.reply("Действие запрещено! Обратитесь к администратору группы.")
    else:
        await update.message.reply("Что-то пошло не так!")


async def check_access(message: types.Message, grant: Grant):
    chat_id = message.chat.id
    user_id = message.from_user.id

    chat_member: ChatMember = await message.chat.get_member(user_id=user_id)

    logging.info(
        f"Request from chat member:"
        f" chat_id=[{chat_id}],"
        f" chat_type=[{message.chat.type}],"
        f" user_id=[{message.from_user.id}],"
        f" chat_member_status=[{chat_member.status}],"
        f" grant=[{grant}]"
    )

    is_private = ChatType.is_private(message)
    is_creator_or_admin = chat_member.is_chat_creator() or chat_member.is_chat_admin()

    if is_private:
        logging.info("No restrictions in private chat")
    elif is_creator_or_admin:
        logging.info("No restrictions for creator or admin")
    else:
        if grant == Grant.READ_ACCESS:
            logging.info("No restrictions for read access")
        elif grant == Grant.WRITE_ACCESS:
            chat = db_select_chat(chat_id=chat_id)
            if not chat:
                raise AuthorizationError("Chat not found => anarchy is disabled by default")
            elif not chat.is_anarchy_enabled:
                raise AuthorizationError("Chat found, anarchy is disabled")
            else:
                logging.info("Anarchy enabled for chat")
        elif grant == Grant.CHANGE_CHAT_SETTINGS:
            raise AuthorizationError("Action allowed only for creator or admin")
        else:
            raise IllegalStateError(f"Unknown grant [{grant}]")


def convert_members_to_names(members: List[Member]) -> List[str]:
    return [x.member_name for x in members]


def convert_members_to_mentions(members: List[Member]) -> List[str]:
    result = []
    for member in members:
        if member.user_id is not None:
            result.append(
                markdown_decoration.link(
                    value=member.member_name,
                    link=f"tg://user?id={member.user_id}"
                )
            )
        else:
            result.append(markdown.escape_md(member.member_name))
    return result


async def shutdown(dispatcher: Dispatcher):
    await dispatcher.storage.close()
    await dispatcher.storage.wait_closed()


if __name__ == '__main__':
    DB_CONNECTION = db_connect()
    db_create_schema()
    executor.start_polling(dp, on_shutdown=shutdown)
    DB_CONNECTION.close()
