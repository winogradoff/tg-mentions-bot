import logging
import os
import re
from dataclasses import dataclass
from typing import Dict, Set

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
conn = psycopg2.connect(DATABASE_URL, sslmode='require')
logging.info("Successful database connection!")

with conn:
    with conn.cursor() as curs:
        curs.execute("SELECT version();")
        record = curs.fetchone()
        print("You are connected to - ", record, "\n")

conn.close()


@dataclass(unsafe_hash=True)
class StorageKey:
    chat_id: str
    group_name: str


@dataclass
class StorageValue:
    members: Set[str]


bot = Bot(token=os.getenv("TOKEN"))
dp = Dispatcher(bot=bot, storage=MemoryStorage())
dp.middleware.setup(LoggingMiddleware())

group_cd = CallbackData('group', 'key', 'action')  # group:<id>:<action>
STORAGE: Dict[StorageKey, StorageValue] = dict()

REGEX_COMMAND_GROUP = re.compile(r'^/(?P<command>[\w-]+)\s+(?P<group>[\w-]+)$')
REGEX_COMMAND_GROUP_MEMBER = re.compile(r'^/(?P<command>[\w-]+)\s+(?P<group>[\w-]+)\s+(?P<member>[@\w-]+)$')


async def shutdown(dispatcher: Dispatcher):
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
            markdown.escape_md('/list_members — список участников группы'),
            markdown.escape_md('/add_member добавление участника в группу'),
            markdown.escape_md('/remove_member — удаление участника из группы'),
            markdown.escape_md('/call — позвать участников группы'),
            markdown.escape_md('/help — справка по всем операциям'),
            sep='\n'
        ),
        parse_mode=ParseMode.MARKDOWN
    )


@dp.message_handler(commands=['list_groups'])
async def handler_list_groups(message: types.Message):
    groups = sorted([x.group_name for x in STORAGE.keys() if x.chat_id == message.chat.id])
    groups = [f"- {x}" for x in groups]
    await message.reply(
        markdown.text(
            markdown_decoration.bold("Все группы:"),
            markdown_decoration.code(
                "\n".join(groups)
                if len(groups) != 0
                else "нет ни одной группы"
            ),
            sep='\n'
        ),
        parse_mode=ParseMode.MARKDOWN
    )


@dp.message_handler(commands=['add_group'])
async def handler_add_group(message: types.Message):
    match = REGEX_COMMAND_GROUP.search(message.text)
    if not match:
        return await message.reply(
            markdown.text(
                markdown_decoration.bold("Пример вызова:"),
                markdown_decoration.code("/add_group group"),
                sep='\n'
            ),
            parse_mode=ParseMode.MARKDOWN
        )
    group_key = match.group("group")
    key = StorageKey(chat_id=message.chat.id, group_name=group_key)
    if key in STORAGE:
        return await message.reply('Такая группа уже существует!')
    STORAGE[key] = StorageValue(members=set())
    await message.reply(
        markdown.text("Группа", markdown_decoration.code(key.group_name), "добавлена!"),
        parse_mode=ParseMode.MARKDOWN
    )


@dp.message_handler(commands=['remove_group'])
async def handler_remove_group(message: types.Message):
    match = REGEX_COMMAND_GROUP.search(message.text)
    if not match:
        return await message.reply(
            markdown.text(
                markdown_decoration.bold("Пример вызова:"),
                markdown_decoration.code("/remove_group group"),
                sep='\n'
            ),
            parse_mode=ParseMode.MARKDOWN
        )
    group_key = match.group("group")
    key = StorageKey(chat_id=message.chat.id, group_name=group_key)
    if key not in STORAGE:
        await message.reply('Группа не найдена!')
    else:
        del STORAGE[key]
        await message.reply(
            markdown.text("Группа", markdown_decoration.bold(key.group_name), "удалена!"),
            parse_mode=ParseMode.MARKDOWN
        )


@dp.message_handler(commands=['list_members'])
async def handler_list_members(message: types.Message):
    match = REGEX_COMMAND_GROUP.search(message.text)
    if not match:
        return await message.reply(
            markdown.text(
                markdown_decoration.bold("Пример вызова:"),
                markdown_decoration.code("/list_members group"),
                sep='\n'
            ),
            parse_mode=ParseMode.MARKDOWN
        )
    group_key = match.group("group")
    key = StorageKey(chat_id=message.chat.id, group_name=group_key)
    if key not in STORAGE:
        await message.reply('Группа не найдена!')
    members = [f"- {x}" for x in sorted(STORAGE[key].members)]
    await message.reply(
        markdown.text(
            markdown.text(
                markdown_decoration.bold("Участники группы"),
                markdown_decoration.code(group_key)
            ),
            markdown_decoration.code(
                "\n".join(members)
                if len(members) != 0
                else "нет ни одного участника"
            ),
            sep='\n'
        ),
        parse_mode=ParseMode.MARKDOWN
    )


@dp.message_handler(commands=['add_member'])
async def handler_add_member(message: types.Message):
    match = REGEX_COMMAND_GROUP_MEMBER.search(message.text)
    if not match:
        return await message.reply(
            markdown.text(
                markdown_decoration.bold("Пример вызова:"),
                markdown_decoration.code("/add_member group username"),
                sep='\n'
            ),
            parse_mode=ParseMode.MARKDOWN
        )
    group_key = match.group('group')
    key = StorageKey(chat_id=message.chat.id, group_name=group_key)
    if key not in STORAGE:
        await message.reply('Группа не найдена!')
    members = STORAGE[key].members
    mentions = [
        x.get_text(message.text)
        for x in message.entities
        if x.type == MessageEntityType.MENTION
    ]
    text_mentions = [
        markdown_decoration.link(value=x.user.full_name, link=x.user.url)
        for x in message.entities
        if x.type == MessageEntityType.TEXT_MENTION
    ]
    all_members = mentions + text_mentions
    if not all_members:
        return await message.reply('Пользователь не найден!')
    if len(all_members) != 1:
        return await message.reply('Пользователь должен быть один!')
    members.update(all_members)
    await message.reply(
        markdown.text(
            "Пользователь добавлен в группу",
            markdown_decoration.code(group_key)
        ),
        parse_mode=ParseMode.MARKDOWN
    )


@dp.message_handler(commands=['remove_member'])
async def handler_remove_member(message: types.Message):
    match = REGEX_COMMAND_GROUP_MEMBER.search(message.text)
    if not match:
        return await message.reply(
            markdown.text(
                markdown_decoration.bold("Пример вызова:"),
                markdown_decoration.code("/remove_member group username"),
                sep='\n'
            ),
            parse_mode=ParseMode.MARKDOWN
        )
    group_key = match.group('group')
    key = StorageKey(chat_id=message.chat.id, group_name=group_key)
    if key not in STORAGE:
        await message.reply('Группа не найдена!')
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
    if not all_members:
        return await message.reply('Пользователь не найден!')
    if len(all_members) != 1:
        return await message.reply('Пользователь должен быть один!')
    STORAGE[key].members.remove(all_members.pop())
    await message.reply(
        markdown.text("Пользователь удалён из группы", markdown_decoration.code(group_key)),
        parse_mode=ParseMode.MARKDOWN
    )


@dp.message_handler(commands=['call'])
async def handler_call(message: types.Message):
    match = REGEX_COMMAND_GROUP.search(message.text)
    if not match:
        return await message.reply(
            markdown.text(
                markdown_decoration.bold("Пример вызова:"),
                markdown_decoration.code("/call group"),
                sep='\n'
            ),
            parse_mode=ParseMode.MARKDOWN
        )
    group_key = match.group("group")
    key = StorageKey(chat_id=message.chat.id, group_name=group_key)
    if key not in STORAGE:
        return await message.reply('Группа не найдена!')
    members = STORAGE[key].members
    if not members:
        return await message.reply('Группа пользователей пуста!')
    members = [markdown.escape_md(x) for x in members]
    text = markdown.text(
        " ".join(members) if len(members) != 0 else "нет ни одного участника",
        sep='\n'
    )
    if message.reply_to_message:
        await message.reply_to_message.reply(text, parse_mode=ParseMode.MARKDOWN)
    else:
        await message.reply(text, parse_mode=ParseMode.MARKDOWN)


if __name__ == '__main__':
    executor.start_polling(dp, on_shutdown=shutdown)
