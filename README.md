# tg-mentions-bot

### Описание

Telegram-бот для упоминания пользователей по настраиваемым группам.

Бот доступен по ссылке https://t.me/TgMentionsBot

### Поддерживаемые команды

```
/groups — просмотр списка групп
/add_group — добавление группы
/remove_group — удаление группы
/add_alias — добавление алиаса группы
/remove_alias — удаление алиаса группы
/members — список пользователей в группе
/add_members — добавление пользователей в группу
/remove_members — удаление пользователей из группы
/enable_anarchy — настраивать бота могут все
/disable_anarchy — настраивать бота могут администраторы и владелец
/call — позвать пользователей группы
/help — справка по всем операциям
```

### Примеры использования

Добавление/удаление группы

```
/add_group group1
/remove_group group1
```

Добавление/удаление синонима (алиаса) для группы

```
/add_alias group1 qqq
/remove_alias qqq
```

Добавление/удаление пользователей

```
/add_members group1 @FirstUser @SecondUser
/remove_members group1 @FirstUser @SecondUser
```

Получение списка групп/пользователей

```
/groups
/members group1
```

Упоминание списка пользователей из группы

```
/call group1
/call qqq
/call group1 какое-то сообщение
```
