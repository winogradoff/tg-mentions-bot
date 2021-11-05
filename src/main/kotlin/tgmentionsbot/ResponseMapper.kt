package tgmentionsbot

import org.apache.commons.text.StringEscapeUtils
import org.springframework.stereotype.Component

@Component
class ResponseMapper {

    fun toHelpResponse() = "<b>Доступные команды:</b>\n" +
            Command.values().joinToString(separator = "\n") { command ->
                listOfNotNull(
                    command.keys.joinToString(separator = ", ") { "/$it" },
                    command.description
                ).joinToString(separator = " — ")
            }

    fun toGroupsResponse(groups: List<GroupWithAliases>): String =
        when {
            groups.isEmpty() -> "Нет ни одной группы!"
            else -> "<b>Вот такие группы существуют:</b>\n" +
                    groups.asSequence()
                        .map { (groupName: GroupName, aliasNames: List<GroupName>) ->
                            "- ${escapeHtml(groupName.value)}${
                                toCommaList(
                                    items = aliasNames,
                                    prefix = " (синонимы: ",
                                    postfix = ")",
                                ) { escapeHtml(it.value) }
                            }"
                        }
                        .joinToString(separator = "\n")
        }

    fun toMembersResponse(groupName: GroupName, members: List<Member>): String =
        when {
            members.isEmpty() -> "В группе ${toGroupName(groupName)} нет ни одного пользователя!"
            else -> "Участники группы ${toGroupName(groupName)}:\n" +
                    toNewlineList(items = members) { "- ${escapeHtml(it.memberName.value)}" }
        }

    fun toCallResponse(groupName: GroupName, members: List<Member>): String =
        when {
            members.isEmpty() -> "В группе ${toGroupName(groupName)} нет ни одного пользователя!"
            else -> "Призываем участников группы ${toGroupName(groupName)}:\n" +
                    toCommaList(members) { toMemberLink(it) }
        }

    fun toAddMembersResponse(groupName: GroupName, members: Set<Member>): String =
        "Пользователи добавленные в группу '$groupName':\n" +
                toNewlineList(items = members) { "- ${toMemberLink(it)}" }

    fun toRemoveMembersResponse(groupName: GroupName, members: Set<Member>): String =
        "Пользователи удалённые из группы '$groupName':\n" +
                members.joinToString(separator = "\n") { "- ${toMemberLink(it)}" }

    fun toAddGroupResponse(groupName: GroupName): String = "Группа ${toGroupName(groupName)} добавлена!"

    fun toAddAliasResponse(groupName: GroupName, aliasName: GroupName): String =
        "Синоним ${toGroupName(aliasName)} для группы ${toGroupName(groupName)} успешно добавлен."

    private fun escapeHtml(str: String): String = StringEscapeUtils.escapeHtml4(str)

    private fun toGroupName(groupName: GroupName): String = "<b>${escapeHtml(groupName.value)}</b>"

    private fun toMemberLink(member: Member): String =
        when {
            member.userId != null -> {
                val userLink = "tg://user?id=${member.userId.value}"
                val userName = escapeHtml(member.memberName.value)
                """<a href="$userLink">$userName</a>"""
            }
            else -> escapeHtml(member.memberName.value)
        }

    private fun <T> toCommaList(
        items: Collection<T>,
        prefix: String = "",
        postfix: String = "",
        transform: (T) -> String
    ): String =
        when {
            items.isEmpty() -> ""
            else -> items.joinToString(
                prefix = prefix,
                postfix = postfix,
                separator = ", ",
                transform = { transform(it) })
        }

    private fun <T> toNewlineList(
        items: Collection<T>,
        prefix: String = "",
        postfix: String = "",
        transform: (T) -> String
    ): String =
        when {
            items.isEmpty() -> ""
            else -> items.joinToString(
                prefix = prefix,
                postfix = postfix,
                separator = "\n",
                transform = { transform(it) })
        }
}
