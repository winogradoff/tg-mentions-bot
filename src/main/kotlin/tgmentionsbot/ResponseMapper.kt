package tgmentionsbot

import org.springframework.stereotype.Component

@Component
class ResponseMapper {

    fun toGroupsResponse(groups: List<GroupWithAliases>): String =
        createHTML {
            bold("Вот такие группы существуют:")
            newline()
            for ((groupName, aliasNames) in groups) {
                text("- ")
                text(groupName.value)
                if (aliasNames.isNotEmpty()) {
                    text(" (синонимы: ")
                    for ((index, a) in aliasNames.withIndex()) {
                        text(a.value)
                        if (index < aliasNames.lastIndex) {
                            text(", ")
                        }
                    }
                    text(")")
                }
                newline()
            }
        }

    fun toMembersResponse(groupName: GroupName, members: List<Member>): String =
        createHTML {
            when {
                members.isEmpty() -> {
                    text("В группе "); bold { escape(groupName.value) }; text(" нет ни одного пользователя!")
                }
                else -> {
                    text("Участники группы "); bold { escape(groupName.value) }; text(":")
                    newline()
                    for (member in members) {
                        pre { text("- "); escape(member.memberName.value) }
                        newline()
                    }
                }
            }
        }

    fun toCallResponse(groupName: GroupName, members: List<Member>): String =
        createHTML {
            when {
                members.isEmpty() -> {
                    text("В группе "); bold { escape(groupName.value) }; text(" нет ни одного пользователя!")
                }
                else -> {
                    text("Призываем участников группы "); bold { escape(groupName.value) }; text(":")
                    newline()
                    for ((index, member) in members.withIndex()) {
                        userMention(member)
                        if (index < members.lastIndex) {
                            text(" ")
                        }
                    }
                }
            }
        }

    fun toHereResponse(members: List<Member>): String =
        createHTML {
            when {
                members.isEmpty() -> {
                    text("В чате нет ни одного пользователя!")
                }
                else -> {
                    text("Призываем участников всех групп:")
                    newline()
                    for ((index, member) in members.withIndex()) {
                        userMention(member)
                        if (index < members.lastIndex) {
                            text(" ")
                        }
                    }
                }
            }
        }

    fun toAddMembersResponse(groupName: GroupName, members: Set<Member>): String =
        createHTML {
            text("В группу "); bold { escape(groupName.value) }; text(" добавлены следующие пользователи:")
            newline()
            for (member in members) {
                pre { text("- "); escape(member.memberName.value) }
                newline()
            }
        }

    fun toRemoveMembersResponse(groupName: GroupName, members: Set<Member>): String =
        createHTML {
            text("Пользователи удалённые из группы "); bold { escape(groupName.value) }; text(":")
            newline()
            for (member in members) {
                pre { text("- "); escape(member.memberName.value) }
                newline()
            }
        }

    fun toPurgeMembersResponse(members: Set<Member>): String =
        createHTML {
            text("Пользователи удалённые из всех групп чата:"); newline()
            for (member in members) {
                pre { text("- "); escape(member.memberName.value) }
                newline()
            }
        }

    fun toAddGroupResponse(groupName: GroupName): String =
        createHTML {
            text("Группа "); bold { escape(groupName.value) }; text(" добавлена!")
        }

    fun toAddAliasResponse(groupName: GroupName, aliasName: GroupName): String =
        createHTML {
            text("Синоним "); bold { escape(aliasName.value) }
            text(" для группы "); bold { escape(groupName.value) }
            text(" успешно добавлен.")
        }

    fun toRemoveAliasResponse(aliasName: GroupName): String =
        createHTML {
            text("Синоним "); bold { escape(aliasName.value) }; text(" был успешно удалён.")
        }

    fun toHelpMessage(command: Command): String =
        createHTML {

            fun commandExample(vararg examples: String) {
                bold("Пример использования:"); newline()

                for ((index, example) in examples.withIndex()) {
                    pre(example)
                    if (index < examples.lastIndex) {
                        newline()
                        text("или")
                        newline()
                    }
                }
            }

            fun constrains(vararg items: Pair<String, String>) {
                bold("Ограничения:"); newline()
                for ((field, message) in items) {
                    text("- $field: $message"); newline()
                }
            }

            fun printCommands(commands: List<Command>) {
                for (c in commands) {
                    for ((index, key) in c.keys.withIndex()) {
                        text("/"); text(key)
                        if (index + 1 < c.keys.size) {
                            text(", ")
                        }
                    }
                    text(" — "); text(c.description)
                    newline()
                }
            }

            when (command) {
                Command.HELP -> {
                    bold("Пример работы с ботом:")
                    newline()
                    pre("/add_group group1"); newline()
                    pre("/add_members group1 @user1 @user2 @user3"); newline()
                    pre("/call group1"); newline()
                    newline()

                    text("Команда "); pre("call")
                    text(" вызовет ранее добавленных пользователей из группы "); pre("group1")
                    text(" вот в таком виде:"); newline()
                    pre("@user1 @user2 @user3")
                    newline(); newline()

                    bold("Общие команды:")
                    newline()
                    printCommands(Command.values().filter { it.access() == Command.Access.COMMON })
                    newline()

                    bold("Административные команды:")
                    newline()
                    printCommands(Command.values().filter { it.access() == Command.Access.ADMIN })
                }

                Command.GROUPS -> commandExample("/groups")

                Command.MEMBERS,
                Command.ADD_GROUP,
                Command.REMOVE_GROUP,
                Command.REMOVE_GROUP_FORCE -> {
                    commandExample("/${command.firstKey()} group")
                    newline(2)
                    constrains("group" to BotConstraints.MESSAGE_FOR_GROUP)
                }

                Command.CALL -> {
                    commandExample(
                        "/${command.firstKey()} group",
                        "/${command.firstKey()} group any long important text",
                    )
                    newline(2)
                    constrains("group" to BotConstraints.MESSAGE_FOR_GROUP)
                }

                Command.HERE -> {
                    commandExample(
                        "/${command.firstKey()} ",
                        "/${command.firstKey()} any long important text",
                    )
                }

                Command.ADD_ALIAS -> {
                    commandExample("/${command.firstKey()} group alias")
                    newline(2)
                    constrains(
                        "group" to BotConstraints.MESSAGE_FOR_GROUP,
                        "alias" to BotConstraints.MESSAGE_FOR_GROUP
                    )
                }

                Command.REMOVE_ALIAS -> {
                    commandExample("/${command.firstKey()} alias")
                    newline(); newline()
                    constrains("alias" to BotConstraints.MESSAGE_FOR_GROUP)
                }

                Command.ADD_MEMBERS,
                Command.REMOVE_MEMBERS -> {
                    commandExample("/${command.firstKey()} group member1 member2")
                    newline(2)
                    constrains(
                        "group" to BotConstraints.MESSAGE_FOR_GROUP,
                        "member" to BotConstraints.MESSAGE_FOR_MEMBER
                    )
                }

                Command.PURGE_MEMBERS -> {
                    commandExample("/${command.firstKey()} member1 member2")
                    newline(2)
                    constrains("member" to BotConstraints.MESSAGE_FOR_MEMBER)
                }

                Command.ENABLE_ANARCHY -> commandExample("/${command.firstKey()}")

                Command.DISABLE_ANARCHY -> commandExample("/${command.firstKey()}")

            }.exhaustive
        }

    private fun HtmlContext.userMention(member: Member) {
        val id = member.userId
        val name = member.memberName.value
        when (id) {
            null -> escape(name)
            else -> link(url = "tg://user?id=${id.value}") { escape(name) }
        }
    }
}
