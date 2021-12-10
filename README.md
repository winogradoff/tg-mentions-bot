# tg-mentions-bot

### Описание

Telegram-бот для упоминания пользователей по настраиваемым группам.

Бот доступен по ссылке https://t.me/TgMentionsBot

### Поддерживаемые команды

```
/help — справка по всем командам
/groups — список групп
/members — список пользователей в группе
/call — позвать пользователей конкретной группы
/here — позвать пользователей из всех групп

/add_group — добавить группу
/remove_group — удалить группу
/remove_group_force — удалить группу со всеми пользователями

/add_alias — добавить синоним группы
/remove_alias — удалить синоним группы

/add_members — добавить пользователей в группу
/remove_members — удалить пользователей из группы
/purge_members — удалить указанных пользователей из всех групп чата

/enable_anarchy — всем доступны настройки
/disable_anarchy — только админам доступны настройки
```

### Примеры использования

Добавление/удаление группы

```
/add_group group1
/remove_group group1
/remove_group_force group1
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
/purge_members @FirstUser @SecondUser
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

Упоминание всех пользователей из всех созданных групп

```
/here
/here какое-то сообщение
```

### База данных

1) поднять локально docker-контейнер с БД:

```shell
docker-compose up -d
```

2) создать конфиг для FlyWay и сохранить его в файл `flyway.dev.conf`:

```conf
flyway.url=jdbc:postgresql://localhost:5432/db
flyway.user=user
flyway.password=pass
flyway.locations=src/main/resources/db/migration
```

3) выполнить для создания всех нужных таблиц:

```shell
flyway -configFiles=./flyway.dev.conf migrate
```

4) остановить и удалить docker-контейнер с БД:

```shell
docker-compose rm -s
```
