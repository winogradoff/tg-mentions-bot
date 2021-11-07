package tgmentionsbot

import org.springframework.stereotype.Component

@Component
class ResponseMapper {

    fun toHelpResponse(): String =
        createHTML {
            bold { text("Доступные команды:") }
            newline()
            for (command in Command.values()) {
                for ((index, key) in command.keys.withIndex()) {
                    text("/"); text(key)
                    if (index + 1 < command.keys.size) {
                        text(", ")
                    }
                }
                if (command.description != null) {
                    text(" — "); text(command.description)
                }
                newline()
            }
        }

    fun toGroupsResponse(groups: List<GroupWithAliases>): String =
        createHTML {
            bold { text("Вот такие группы существуют:") }
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
                        val id = member.userId
                        val name = member.memberName.value
                        when (id) {
                            null -> escape(name)
                            else -> link(url = "tg://user?id=${id.value}") { escape(name) }
                        }
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
            }
        }

    fun toRemoveMembersResponse(groupName: GroupName, members: Set<Member>): String =
        createHTML {
            text("Пользователи удалённые из группы "); bold { escape(groupName.value) }; text(":")
            newline()
            for (member in members) {
                pre { text("- "); escape(member.memberName.value) }
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
}