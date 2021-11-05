package tgmentionsbot

import org.springframework.stereotype.Component
import org.telegram.telegrambots.meta.api.objects.EntityType
import org.telegram.telegrambots.meta.api.objects.Message
import org.telegram.telegrambots.meta.api.objects.MessageEntity
import org.telegram.telegrambots.meta.api.objects.Update
import org.telegram.telegrambots.meta.api.objects.chatmember.ChatMember
import org.telegram.telegrambots.meta.api.objects.chatmember.ChatMemberAdministrator
import org.telegram.telegrambots.meta.api.objects.chatmember.ChatMemberOwner

@Component
class RequestMapper {

    fun isCommandMessage(update: Update): Boolean =
        update.message
            ?.let { getAnyMessageEntities(it) }
            ?.any { isBotCommand(it) }
            ?: false

    fun isPrivateChat(message: Message) =
        message.chat.type == CHAT_TYPE_PRIVATE

    fun isOwnerOrAdmin(message: Message, members: List<ChatMember>): Boolean {
        val fromUserId = requireNotNull(message.from).id
        for (member in members) {
            when {
                member is ChatMemberOwner && member.user.id == fromUserId -> return true
                member is ChatMemberAdministrator && member.user.id == fromUserId -> return true
            }
        }
        return false
    }

    fun parseCommand(message: Message): Command? =
        Command.getByKey(getAnyMessageEntities(message).single { isBotCommand(it) }.text)

    fun parseGroup(message: Message): GroupName {
        val matchResult = parseByRegex(
            message = message,
            pattern = BotConstraints.REGEX_CMD_GROUP,
            userConstraintDetails = mapOf("group" to BotConstraints.MESSAGE_FOR_GROUP)
        )
        return GroupName(requireNotNull(matchResult.groups["group"]?.value))
    }

    fun parseGroupWithTail(message: Message): GroupName {
        val matchResult = parseByRegex(
            message = message,
            pattern = BotConstraints.REGEX_CMD_GROUP_WITH_TAIL,
            userConstraintDetails = mapOf("group" to BotConstraints.MESSAGE_FOR_GROUP)
        )
        return GroupName(requireNotNull(matchResult.groups["group"]?.value))
    }

    fun parseGroupWithAlias(message: Message): Pair<GroupName, GroupName> {
        val matchResult: MatchResult = parseByRegex(
            message = message,
            pattern = BotConstraints.REGEX_CMD_GROUP_ALIAS,
            userConstraintDetails = mapOf(
                "group" to BotConstraints.MESSAGE_FOR_GROUP,
                "alias" to BotConstraints.MESSAGE_FOR_GROUP
            )
        )
        val groupName = GroupName(requireNotNull(matchResult.groups["group"]?.value))
        val aliasName = GroupName(requireNotNull(matchResult.groups["alias"]?.value))
        return groupName to aliasName
    }

    fun parseGroupWithMembers(message: Message): Pair<GroupName, Set<Member>> {
        val matchResult: MatchResult = parseByRegex(
            message = message,
            pattern = BotConstraints.REGEX_CMD_GROUP_MEMBERS,
            userConstraintDetails = mapOf(
                "group" to BotConstraints.MESSAGE_FOR_GROUP,
                "username" to BotConstraints.MESSAGE_FOR_MEMBER
            )
        )
        val groupName = GroupName(requireNotNull(matchResult.groups["group"]?.value))
        val members: Set<Member> = (message.entities ?: emptyList())
            .asSequence()
            .mapNotNull {
                when (it.type) {
                    EntityType.MENTION -> Member(memberName = MemberName(it.text))
                    EntityType.TEXTMENTION -> Member(memberName = MemberName(it.text), userId = UserId(it.user.id))
                    else -> null
                }
            }
            .toSet()
        return groupName to members
    }

    private fun getAnyMessageEntities(message: Message): List<MessageEntity> =
        message.entities ?: message.captionEntities ?: emptyList()

    private fun isBotCommand(messageEntity: MessageEntity): Boolean =
        messageEntity.offset == 0 && messageEntity.type == EntityType.BOTCOMMAND

    private fun parseByRegex(
        message: Message,
        pattern: Regex,
        userConstraintDetails: Map<String, String>
    ): MatchResult =
        pattern.matchEntire(message.text ?: message.caption)
            ?: throw BotReplyException.ValidationError(
                message = "Match error with pattern=[$pattern]",
                userMessage = "Ограничения:\n" +
                        userConstraintDetails.entries
                            .joinToString(separator = ",\n") { "${it.key}=[${it.value}]" }
            )

    companion object {
        private const val CHAT_TYPE_PRIVATE = "private"
    }
}
