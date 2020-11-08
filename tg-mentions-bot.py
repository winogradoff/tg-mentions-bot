import logging
import os
from json import JSONDecodeError
from typing import List, Dict

import aiogram.types as types
from aiogram import Bot, Dispatcher
from aiogram.contrib.fsm_storage.memory import MemoryStorage
from aiogram.contrib.middlewares.logging import LoggingMiddleware
from aiogram.types import ParseMode, MessageEntityType, ChatMember, ChatType, InlineKeyboardButton, InlineKeyboardMarkup
from aiogram.utils import markdown, executor
from aiogram.utils.exceptions import MessageNotModified
from aiogram.utils.text_decorations import markdown_decoration

import constraints
import database as db
from models import Grant, GroupAlias, Member, AuthorizationError, IllegalStateError, CallbackData

logging.basicConfig(
    format=u'%(filename)+13s [ LINE:%(lineno)-4s] %(levelname)-8s [%(asctime)s] %(message)s',
    level=logging.DEBUG
)

bot = Bot(token=os.getenv("TOKEN"))
dp = Dispatcher(bot=bot, storage=MemoryStorage())
dp.middleware.setup(LoggingMiddleware())


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

    with db.get_connection() as conn:
        aliases: List[GroupAlias] = db.select_group_aliases_by_chat_id(conn, chat_id=message.chat.id)
        if len(aliases) == 0:
            return await message.reply("Нет ни одной группы.", parse_mode=ParseMode.MARKDOWN)

        aliases_lookup: Dict[int, List[GroupAlias]] = {}
        for a in aliases:
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
    await check_access(message, Grant.WRITE_ACCESS)
    match = constraints.REGEX_CMD_GROUP.search(message.text)
    if not match:
        return await message.reply(
            markdown.text(
                markdown_decoration.bold("Пример вызова:"),
                markdown_decoration.code("/add_group group"),
                " ",
                markdown_decoration.bold("Ограничения:"),
                markdown.text("group:", constraints.MESSAGE_FOR_GROUP),
                sep='\n'
            ),
            parse_mode=ParseMode.MARKDOWN
        )
    group_name = match.group("group")

    if len(group_name) > constraints.MAX_GROUP_NAME_LENGTH:
        return await message.reply('Слишком длинное название группы!')

    with db.get_connection() as conn:
        db.insert_chat(conn, chat_id=message.chat.id)
        db.select_chat_for_update(conn, chat_id=message.chat.id)

        existing_groups: List[GroupAlias] = db.select_group_aliases_by_chat_id(conn, chat_id=message.chat.id)

        if group_name in {x.alias_name for x in existing_groups}:
            return await message.reply('Такая группа уже существует!')

        if len({x.group_id for x in existing_groups}) >= constraints.MAX_GROUPS_PER_CHAT:
            return await message.reply(
                f'Слишком много групп уже создано!'
                f' Текущее ограничение для чата: {constraints.MAX_GROUPS_PER_CHAT}'
            )

        group_id = db.insert_group(conn, chat_id=message.chat.id)
        db.insert_group_alias(
            conn,
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
    match = constraints.REGEX_CMD_GROUP.search(message.text)
    if not match:
        return await message.reply(
            markdown.text(
                markdown_decoration.bold("Пример вызова:"),
                markdown_decoration.code("/remove_group group"),
                " ",
                markdown_decoration.bold("Ограничения:"),
                markdown.text("group:", constraints.MESSAGE_FOR_GROUP),
                sep='\n'
            ),
            parse_mode=ParseMode.MARKDOWN
        )
    group_name = match.group("group")

    with db.get_connection() as conn:
        db.select_chat_for_update(conn, chat_id=message.chat.id)
        group = db.select_group_by_alias_name(conn, chat_id=message.chat.id, alias_name=group_name)
        if not group:
            return await message.reply(
                markdown.text('Группа', markdown_decoration.code(group_name), 'не найдена!'),
                parse_mode=ParseMode.MARKDOWN
            )
        logging.info(f"group: {group}")
        members = db.select_members(conn, group.group_id)
        if len(members) != 0:
            logging.info(f"members: {members}")
            return await message.reply('Группу нельзя удалить, в ней есть пользователи!')

        group_aliases = db.select_group_aliases_by_group_id(conn, group_id=group.group_id)

        for a in group_aliases:
            db.delete_group_alias(conn, alias_id=a.alias_id)

        db.delete_group(conn, group_id=group.group_id)

    await message.reply(
        markdown.text("Группа", markdown_decoration.bold(group_name), "удалена!"),
        parse_mode=ParseMode.MARKDOWN
    )


@dp.message_handler(commands=['add_group_alias'])
async def handler_add_group_alias(message: types.Message):
    await check_access(message, Grant.WRITE_ACCESS)
    match = constraints.REGEX_CMD_GROUP_ALIAS.search(message.text)
    if not match:
        return await message.reply(
            markdown.text(
                markdown_decoration.bold("Пример вызова:"),
                markdown_decoration.code("/add_group_aliases group alias"),
                " ",
                markdown_decoration.bold("Ограничения:"),
                markdown.text("group:", constraints.MESSAGE_FOR_GROUP),
                markdown.text("alias:", constraints.MESSAGE_FOR_GROUP),
                sep='\n'
            ),
            parse_mode=ParseMode.MARKDOWN
        )
    group_name = match.group('group')
    group_alias = match.group('alias')

    if len(group_alias) > constraints.MAX_GROUP_NAME_LENGTH:
        return await message.reply('Слишком длинное название группы!')

    with db.get_connection() as conn:
        db.select_chat_for_update(conn, chat_id=message.chat.id)
        group = db.select_group_by_alias_name(conn, chat_id=message.chat.id, alias_name=group_name)
        if not group:
            return await message.reply(
                markdown.text('Группа', markdown_decoration.code(group_name), 'не найдена!'),
                parse_mode=ParseMode.MARKDOWN
            )
        logging.info(f"group: {group}")

        aliases: List[GroupAlias] = db.select_group_aliases_by_chat_id(conn, chat_id=message.chat.id)

        if group_alias in set(x.alias_name for x in aliases):
            return await message.reply("Такой алиас уже используется!")

        if len([x for x in aliases if x.group_id == group.group_id]) >= constraints.MAX_ALIASES_PER_GROUP:
            return await message.reply(
                f"Нельзя добавить так много алиасов!"
                f" Текущее ограничение для одной группы: {constraints.MAX_ALIASES_PER_GROUP}"
            )

        db.insert_group_alias(
            conn,
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
    match = constraints.REGEX_CMD_GROUP_ALIAS.search(message.text)
    if not match:
        return await message.reply(
            markdown.text(
                markdown_decoration.bold("Пример вызова:"),
                markdown_decoration.code("/remove_group_alias group alias"),
                " ",
                markdown_decoration.bold("Ограничения:"),
                markdown.text("group:", constraints.MESSAGE_FOR_GROUP),
                markdown.text("alias:", constraints.MESSAGE_FOR_GROUP),
                sep='\n'
            ),
            parse_mode=ParseMode.MARKDOWN
        )
    group_name = match.group('group')
    alias_name = match.group('alias')

    with db.get_connection() as conn:
        db.select_chat_for_update(conn, chat_id=message.chat.id)
        group = db.select_group_by_alias_name(conn, chat_id=message.chat.id, alias_name=group_name)
        if not group:
            return await message.reply(
                markdown.text('Группа', markdown_decoration.code(group_name), 'не найдена!'),
                parse_mode=ParseMode.MARKDOWN
            )
        logging.info(f"group: {group}")

        group_aliases: Dict[str, GroupAlias] = {
            x.alias_name: x
            for x in db.select_group_aliases_by_group_id(conn, group_id=group.group_id)
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

        db.delete_group_alias(conn, alias_id=group_alias.alias_id)

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
    match = constraints.REGEX_CMD_GROUP.search(message.text)
    if not match:
        return await message.reply(
            markdown.text(
                markdown_decoration.bold("Пример вызова:"),
                markdown_decoration.code("/list_members group"),
                " ",
                markdown_decoration.bold("Ограничения:"),
                markdown.text("group:", constraints.MESSAGE_FOR_GROUP),
                sep='\n'
            ),
            parse_mode=ParseMode.MARKDOWN
        )
    group_name = match.group("group")

    with db.get_connection() as conn:
        group = db.select_group_by_alias_name(conn, chat_id=message.chat.id, alias_name=group_name)
        if not group:
            return await message.reply(
                markdown.text('Группа', markdown_decoration.code(group_name), 'не найдена!'),
                parse_mode=ParseMode.MARKDOWN
            )
        members = db.select_members(conn, group_id=group.group_id)

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
    match = constraints.REGEX_CMD_GROUP_MEMBERS.search(message.text)
    if not match:
        return await message.reply(
            markdown.text(
                markdown_decoration.bold("Пример вызова:"),
                markdown_decoration.code("/add_members group username1 username2"),
                " ",
                markdown_decoration.bold("Ограничения:"),
                markdown.text("group:", constraints.MESSAGE_FOR_GROUP),
                markdown.text("username:", constraints.MESSAGE_FOR_MEMBER),
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

    with db.get_connection() as conn:
        db.select_chat_for_update(conn, chat_id=message.chat.id)

        group = db.select_group_by_alias_name(conn, chat_id=message.chat.id, alias_name=group_name)
        if not group:
            return await message.reply(
                markdown.text('Группа', markdown_decoration.code(group_name), 'не найдена!'),
                parse_mode=ParseMode.MARKDOWN
            )
        logging.info(f"group: {group}")

        existing_members: List[Member] = db.select_members(conn, group_id=group.group_id)

        if len(existing_members) + len(all_members) > constraints.MAX_MEMBERS_PER_GROUP:
            return await message.reply(
                f'Слишком много пользователей уже добавлено в группу!'
                f' Текущее ограничение для одной группы: {constraints.MAX_MEMBERS_PER_GROUP}'
            )

        for member in all_members:
            db.insert_member(conn, group_id=group.group_id, member=member)

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
    match = constraints.REGEX_CMD_GROUP_MEMBERS.search(message.text)
    if not match:
        return await message.reply(
            markdown.text(
                markdown_decoration.bold("Пример вызова:"),
                markdown_decoration.code("/remove_members group username1 username2"),
                " ",
                markdown_decoration.bold("Ограничения:"),
                markdown.text("group:", constraints.MESSAGE_FOR_GROUP),
                markdown.text("username:", constraints.MESSAGE_FOR_MEMBER),
                sep='\n'
            ),
            parse_mode=ParseMode.MARKDOWN
        )
    group_name = match.group('group')

    with db.get_connection() as conn:
        db.select_chat_for_update(conn, chat_id=message.chat.id)

        group = db.select_group_by_alias_name(conn, chat_id=message.chat.id, alias_name=group_name)
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

        for member in all_members:
            db.delete_member(conn, group_id=group.group_id, member_name=member)

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
    match = constraints.REGEX_CMD_GROUP_MESSAGE.search(message.text)
    if not match:
        return await message.reply(
            markdown.text(
                markdown_decoration.bold("Пример вызова:"),
                markdown_decoration.code("/call group"),
                " ",
                markdown_decoration.bold("Ограничения:"),
                markdown.text("group:", constraints.MESSAGE_FOR_GROUP),
                sep='\n'
            ),
            parse_mode=ParseMode.MARKDOWN
        )
    group_name = match.group("group")

    with db.get_connection() as conn:
        group = db.select_group_by_alias_name(conn, chat_id=message.chat.id, alias_name=group_name)
        if not group:
            return await message.reply(
                markdown.text('Группа', markdown_decoration.code(group_name), 'не найдена!'),
                parse_mode=ParseMode.MARKDOWN
            )
        logging.info(f"group: {group}")
        members = db.select_members(conn, group_id=group.group_id)

    if len(members) == 0:
        return await message.reply('Группа пользователей пуста!')

    mentions = convert_members_to_mentions(members)

    text = " ".join(mentions)
    if message.reply_to_message:
        await message.reply_to_message.reply(text, parse_mode=ParseMode.MARKDOWN)
    else:
        await message.reply(text, parse_mode=ParseMode.MARKDOWN)


@dp.message_handler(commands=['test'])
async def handler_test(message: types.Message):
    await check_access(message, grant=Grant.READ_ACCESS)

    with db.get_connection() as conn:
        aliases: List[GroupAlias] = db.select_group_aliases_by_chat_id(conn, chat_id=message.chat.id)
        if len(aliases) == 0:
            return await message.reply("Нет ни одной группы.", parse_mode=ParseMode.MARKDOWN)

        aliases_lookup: Dict[int, List[GroupAlias]] = {}
        for a in aliases:
            aliases_lookup.setdefault(a.group_id, []).append(a)

    inline_keyboard = InlineKeyboardMarkup()

    groups_for_print = []
    for group_id in sorted({x.group_id for x in aliases}):
        group_aliases = sorted(aliases_lookup.get(group_id, []), key=lambda x: x.alias_id)
        group_aliases = [x.alias_name for x in group_aliases]
        head, *tail = group_aliases
        tail = f" (синонимы: {', '.join(tail)})" if len(tail) > 0 else ""
        groups_for_print.append(f"{head}{tail}")

        inline_keyboard.add(
            InlineKeyboardButton(
                text=f"{head}{tail}",
                callback_data=CallbackData(
                    group_id=group_id,
                    chat_id=message.chat.id,
                    user_id=message.from_user.id
                ).to_json()
            )
        )

    await message.reply(
        markdown_decoration.bold("Выбери группу"),
        parse_mode=ParseMode.MARKDOWN,
        reply_markup=inline_keyboard
    )


@dp.callback_query_handler(lambda c: len(c.data) > 0)
async def process_callback_test(callback_query: types.CallbackQuery):
    chat_id = callback_query.message.chat.id
    user_id = callback_query.from_user.id

    try:
        callback_data = CallbackData.from_json(callback_query.data)
    except JSONDecodeError:
        logging.warning(f"Callback data deserialize error: data=[{callback_query.data}]")
        await bot.answer_callback_query(
            callback_query_id=callback_query.id,
            text="Что-то пошло не так!"
        )
        return await callback_query.message.delete()

    if callback_data.chat_id != chat_id or callback_data.user_id != user_id:
        logging.warning(
            f"Wrong chat or user:"
            f" chat_id=[{chat_id}],"
            f" user_id=[{user_id}],"
            f" callback_data={callback_data}"
        )
        return await bot.answer_callback_query(
            callback_query_id=callback_query.id,
            text="Это чужой диалог!",
            show_alert=True
        )

    with db.get_connection() as conn:
        members = db.select_members(conn, group_id=callback_data.group_id)

    if len(members) == 0:
        return await bot.answer_callback_query(
            callback_query_id=callback_query.id,
            text="Эта группа пуста! Выберите другую.",
            show_alert=True
        )

    await bot.answer_callback_query(callback_query.id)

    mentions = convert_members_to_mentions(members)
    await callback_query.message.edit_text(" ".join(mentions), parse_mode=ParseMode.MARKDOWN)


@dp.message_handler(commands=['enable_anarchy'])
async def handler_enable_anarchy(message: types.Message):
    await check_access(message, Grant.CHANGE_CHAT_SETTINGS)
    with db.get_connection() as conn:
        db.insert_chat(conn, chat_id=message.chat.id)
        db.set_chat_anarchy(conn, chat_id=message.chat.id, is_anarchy_enabled=True)
    await message.reply("Анархия включена")


@dp.message_handler(commands=['disable_anarchy'])
async def handler_disable_anarchy(message: types.Message):
    await check_access(message, Grant.CHANGE_CHAT_SETTINGS)
    with db.get_connection() as conn:
        db.insert_chat(conn, chat_id=message.chat.id)
        db.set_chat_anarchy(conn, chat_id=message.chat.id, is_anarchy_enabled=False)
    await message.reply("Анархия выключена")


@dp.errors_handler()
async def handler_error(update, error):
    if isinstance(error, MessageNotModified):
        return True
    elif isinstance(error, AuthorizationError):
        await update.message.reply("Действие запрещено! Обратитесь к администратору группы.")
    else:
        logging.error("Unexpected error", error)
        if update.message:
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
            with db.get_connection() as conn:
                chat = db.select_chat(conn, chat_id=chat_id)
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


def main():
    try:
        db.create_pool()
        with db.get_connection() as conn:
            db.create_schema(conn)
        executor.start_polling(dp, on_shutdown=shutdown)
    finally:
        db.close_pool()


if __name__ == '__main__':
    main()
