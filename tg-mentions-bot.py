import logging
import os
import re
import textwrap
from dataclasses import dataclass
from typing import List, Optional

import psycopg2
from aiogram import Bot, Dispatcher, types
from aiogram.contrib.fsm_storage.memory import MemoryStorage
from aiogram.contrib.middlewares.logging import LoggingMiddleware
from aiogram.types import ParseMode, MessageEntityType
from aiogram.utils import executor
from aiogram.utils import markdown
from aiogram.utils.callback_data import CallbackData
from aiogram.utils.exceptions import MessageNotModified
from aiogram.utils.text_decorations import markdown_decoration

logging.basicConfig(
    format=u'%(filename)+13s [ LINE:%(lineno)-4s] %(levelname)-8s [%(asctime)s] %(message)s',
    level=logging.DEBUG
)

DATABASE_URL = os.environ['DATABASE_URL']

logging.info("Connection to DB...")
# todo: connection pool
connection = psycopg2.connect(DATABASE_URL, sslmode='require')
logging.info("Successful database connection!")

with connection:
    with connection.cursor() as cursor:
        cursor.execute("SELECT version();")
        record = cursor.fetchone()
        logging.info(f"You are connected to - {record}")

with connection:
    with connection.cursor() as cursor:
        logging.info("Database schema creation...")
        cursor.execute(textwrap.dedent(
            """
                create table if not exists chat
                (
                    chat_id bigint not null primary key
                );
                
                create table if not exists chat_group
                (
                    group_id   bigserial primary key,
                    group_name varchar(200) not null,
                    chat_id    bigint       not null,
                    foreign key (chat_id) references chat (chat_id)
                );
                
                create table if not exists member
                (
                    member_id   bigserial primary key,
                    group_id    bigint       not null,
                    member_name varchar(200) not null,
                    user_id     bigint           null,
                    foreign key (group_id) references chat_group (group_id)
                );
                
                create unique index if not exists idx_chat_group on chat_group (chat_id, group_name);
                create unique index if not exists idx_member on member (group_id, member_name);
            """
        ))
        logging.info("Database schema was created successfully!")


@dataclass
class Group:
    group_id: int
    group_name: str


@dataclass
class Member:
    member_name: str
    member_id: Optional[int] = None
    user_id: Optional[int] = None


bot = Bot(token=os.getenv("TOKEN"))
dp = Dispatcher(bot=bot, storage=MemoryStorage())
dp.middleware.setup(LoggingMiddleware())

group_cd = CallbackData('group', 'key', 'action')  # group:<id>:<action>

REGEX_CMD = r"[@a-zA-Z0-9-_]+"
REGEX_GROUP = r"[a-zA-Z0-9а-яА-ЯёЁ-_]+"
REGEX_MEMBER = r"[@\w-]+"

REGEX_CMD_GROUP = re.compile(fr"^/({REGEX_CMD})\s+(?P<group>{REGEX_GROUP})$")
REGEX_CMD_GROUP_MESSAGE = re.compile(fr'^/({REGEX_CMD})\s+(?P<group>{REGEX_GROUP})(\s+(.|\n)*)*')
REGEX_CMD_GROUP_MEMBERS = re.compile(fr'^/({REGEX_CMD})\s+(?P<group>{REGEX_GROUP})(\s+(?P<member>{REGEX_MEMBER}))+$')


def db_get_groups(chat_id: int) -> List[Group]:
    with connection:
        with connection.cursor() as cursor:
            cursor.execute(
                "select group_id, group_name"
                " from chat_group"
                " where chat_id = %s",
                (chat_id,)
            )
            return [Group(group_id=x[0], group_name=x[1]) for x in cursor.fetchall()]


def db_get_group(chat_id: int, group_name: str) -> Optional[Group]:
    with connection:
        with connection.cursor() as cursor:
            cursor.execute(
                "select group_id, group_name"
                " from chat_group"
                " where chat_id = %s and group_name = %s",
                (chat_id, group_name,)
            )
            row = cursor.fetchone()
            if not row:
                return None
            return Group(group_id=row[0], group_name=row[1])


def db_get_members(group_id: int) -> List[Member]:
    with connection:
        with connection.cursor() as cursor:
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
    with connection:
        with connection.cursor() as cursor:
            cursor.execute(
                "insert into chat (chat_id)"
                " values (%s) on conflict do nothing",
                (chat_id,)
            )


def db_insert_group(chat_id: int, group_name: str):
    logging.info(f"DB: inserting group: chat_id=[{chat_id}], group_name=[{group_name}]")
    with connection:
        with connection.cursor() as cursor:
            cursor.execute(
                "insert into chat_group (chat_id, group_name)"
                " values (%s, %s) on conflict do nothing",
                (chat_id, group_name,)
            )


def db_insert_member(group_id: int, member: Member):
    logging.info(f"DB: inserting member: group_id=[{group_id}], member=[{member}]")
    with connection:
        with connection.cursor() as cursor:
            cursor.execute(
                "insert into member (group_id, member_name, user_id)"
                " values (%s, %s, %s) on conflict do nothing",
                (group_id, member.member_name, member.user_id,)
            )


def db_delete_group(group_id: int):
    logging.info(f"DB: deleting group: group_id=[{group_id}]")
    with connection:
        with connection.cursor() as cursor:
            cursor.execute("delete from chat_group where group_id = %s", (group_id,))


def db_delete_member(group_id: int, member_name: str):
    logging.info(f"DB: deleting member: group_id=[{group_id}], member_name=[{member_name}]")
    with connection:
        with connection.cursor() as cursor:
            cursor.execute(
                "delete from member where group_id = %s and member_name = %s",
                (group_id, member_name,)
            )


async def shutdown(dispatcher: Dispatcher):
    connection.close()
    await dispatcher.storage.close()
    await dispatcher.storage.wait_closed()


@dp.errors_handler(exception=MessageNotModified)
async def handler_message_not_modified(update, error):
    return True  # errors_handler must return True if error was handled correctly


@dp.message_handler(commands=['start', 'help'])
async def handler_help(message: types.Message):
    await message.reply(
        text=markdown.text(
            f"Привет, {message.from_user.get_mention()}! 👋",
            "",
            markdown_decoration.bold('Я поддерживаю команды:'),
            markdown.escape_md('/list_groups — просмотр списка групп'),
            markdown.escape_md('/add_group — добавление группы'),
            markdown.escape_md('/remove_group — удаление группы'),
            markdown.escape_md('/list_members — список пользователей в группе'),
            markdown.escape_md('/add_members — добавление пользователей в группу'),
            markdown.escape_md('/remove_members — удаление пользователей из группы'),
            markdown.escape_md('/call — позвать пользователей группы'),
            markdown.escape_md('/help — справка по всем операциям'),
            sep='\n'
        ),
        parse_mode=ParseMode.MARKDOWN
    )


@dp.message_handler(commands=['list_groups'])
async def handler_list_groups(message: types.Message):
    groups = db_get_groups(chat_id=message.chat.id)
    groups = sorted([x.group_name for x in groups])
    logging.info(f"groups: {groups}")

    if len(groups) == 0:
        text = "Нет ни одной группы."
    else:
        text = markdown.text(
            markdown_decoration.bold("Вот такие группы существуют:"),
            markdown_decoration.code("\n".join([f"- {x}" for x in groups])),
            sep='\n'
        )

    await message.reply(text, parse_mode=ParseMode.MARKDOWN)


@dp.message_handler(commands=['add_group'])
async def handler_add_group(message: types.Message):
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

    if len(group_name) > 20:
        return await message.reply('Слишком длинное название группы!')

    group = db_get_group(chat_id=message.chat.id, group_name=group_name)
    if group:
        logging.info(f"group: {group}")
        return await message.reply('Такая группа уже существует!')

    db_insert_chat(chat_id=message.chat.id)
    db_insert_group(chat_id=message.chat.id, group_name=group_name)

    await message.reply(
        markdown.text("Группа", markdown_decoration.code(group_name), "добавлена!"),
        parse_mode=ParseMode.MARKDOWN
    )


@dp.message_handler(commands=['remove_group'])
async def handler_remove_group(message: types.Message):
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
    group = db_get_group(chat_id=message.chat.id, group_name=group_name)
    if not group:
        return await message.reply(
            markdown.text('Группа', markdown_decoration.code(group_name), 'не найдена!'),
            parse_mode=ParseMode.MARKDOWN
        )
    logging.info(f"group: {group}")

    members = db_get_members(group.group_id)
    if len(members) != 0:
        logging.info(f"members: {members}")
        return await message.reply('Группу нельзя удалить, в ней есть пользователи!')

    try:
        db_delete_group(group_id=group.group_id)
    except (Exception, psycopg2.Error) as error:
        logging.error("Error for delete operation", error)
        return await message.reply('Возникла ошибка при удалении группы!')

    await message.reply(
        markdown.text("Группа", markdown_decoration.bold(group_name), "удалена!"),
        parse_mode=ParseMode.MARKDOWN
    )


@dp.message_handler(commands=['list_members'])
async def handler_list_members(message: types.Message):
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
    group = db_get_group(chat_id=message.chat.id, group_name=group_name)
    if not group:
        return await message.reply(
            markdown.text('Группа', markdown_decoration.code(group_name), 'не найдена!'),
            parse_mode=ParseMode.MARKDOWN
        )

    members = db_get_members(group_id=group.group_id)
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
    group = db_get_group(chat_id=message.chat.id, group_name=group_name)
    if not group:
        return await message.reply(
            markdown.text('Группа', markdown_decoration.code(group_name), 'не найдена!'),
            parse_mode=ParseMode.MARKDOWN
        )
    logging.info(f"group: {group}")

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

    with connection:
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
    group = db_get_group(chat_id=message.chat.id, group_name=group_name)
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

    with connection:
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
    group = db_get_group(chat_id=message.chat.id, group_name=group_name)
    if not group:
        return await message.reply(
            markdown.text('Группа', markdown_decoration.code(group_name), 'не найдена!'),
            parse_mode=ParseMode.MARKDOWN
        )
    logging.info(f"group: {group}")

    members = db_get_members(group_id=group.group_id)
    if len(members) == 0:
        return await message.reply('Группа пользователей пуста!')

    mentions = convert_members_to_mentions(members)

    text = " ".join(mentions)
    if message.reply_to_message:
        await message.reply_to_message.reply(text, parse_mode=ParseMode.MARKDOWN)
    else:
        await message.reply(text, parse_mode=ParseMode.MARKDOWN)


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


if __name__ == '__main__':
    executor.start_polling(dp, on_shutdown=shutdown)
