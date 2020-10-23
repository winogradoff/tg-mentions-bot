# tg-mentions-bot
Telegram-бот для упоминания пользователей по настраиваемым группам.

Поддерживаются следующие команды:

```
/list_groups — просмотр списка групп
/add_group — добавление группы
/remove_group — удаление группы
/list_members — список пользователей в группе
/add_members — добавление пользователей в группу
/remove_members — удаление пользователей из группы
/call — позвать пользователей группы
/help — справка по всем операциям
```

## Примеры

Добавление/удаление группы
```
/add_group qwe
/remove_group qwe
```

Добавление/удаление пользователей
```
/add_members qwe @FirstUser @SecondUser
/remove_members qwe @FirstUser @SecondUser
```

Получение списка групп/пользователей
```
/list_groups
/list_members qwe
```

Упоминание списка пользователей из группы
```
/call qwe
/call qwe какое-то сообщений
```
