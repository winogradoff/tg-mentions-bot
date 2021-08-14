package tgmentionsbot

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.telegram.telegrambots.bots.TelegramLongPollingBot
import org.telegram.telegrambots.meta.api.methods.groupadministration.GetChatAdministrators
import org.telegram.telegrambots.meta.api.methods.send.SendMessage
import org.telegram.telegrambots.meta.api.objects.EntityType
import org.telegram.telegrambots.meta.api.objects.Message
import org.telegram.telegrambots.meta.api.objects.MessageEntity
import org.telegram.telegrambots.meta.api.objects.Update
import org.telegram.telegrambots.meta.api.objects.chatmember.ChatMember
import org.telegram.telegrambots.meta.api.objects.chatmember.ChatMemberAdministrator
import org.telegram.telegrambots.meta.api.objects.chatmember.ChatMemberOwner
import tgmentionsbot.Command.*


@Component
class Bot(
    private val botProperties: BotProperties,
    private val botService: BotService
) : TelegramLongPollingBot() {

    private val logger = LoggerFactory.getLogger(this.javaClass)

    override fun getBotToken(): String = botProperties.botToken
    override fun getBotUsername(): String = botProperties.botUsername

    override fun onUpdateReceived(update: Update) {

        logger.debug("Update: $update")

        if (!update.isCommandMessage()) return

        val updateMessage: Message = update.message

        when (val command: Command? = updateMessage.parseCommand()) {
            null -> logger.warn("Unknown command: [$updateMessage]")
            else -> {
                logger.info("Command: [$command]")
                updateMessage.catchException {
                    routeCommand(
                        command = command,
                        message = updateMessage,
                        chat = Chat(
                            chatId = ChatId(updateMessage.chatId),
                            chatTitle = updateMessage.chat.title,
                            chatUserName = updateMessage.chat.userName
                        )
                    )
                }
            }
        }
    }

    private fun routeCommand(command: Command, message: Message, chat: Chat) = when (command) {

        HELP -> {
            message.checkAccess(Grant.READ_ACCESS)
            message.sendReply(mapHelpResponse())
        }

        GROUPS -> {
            message.checkAccess(Grant.READ_ACCESS)
            message.sendReply(mapGroupsResponse(botService.getGroups(chat.chatId)))
        }

        MEMBERS -> {
            message.checkAccess(Grant.READ_ACCESS)
            val groupName = message.parseGroup()
            val members = botService.getMembers(chatId = chat.chatId, groupName = groupName)
            message.sendReply(mapMembersResponse(groupName, members))
        }

        CALL -> {
            message.checkAccess(Grant.READ_ACCESS)
            val groupName = message.parseGroupWithTail()
            val members = botService.getMembers(chatId = chat.chatId, groupName = groupName)
            message.sendReply(mapCallResponse(groupName, members))
        }

        ADD_GROUP -> {
            message.checkAccess(Grant.WRITE_ACCESS)
            botService.addGroup(chat = chat, groupName = message.parseGroup())
            message.sendReply("Группа добавлена.")
        }

        REMOVE_GROUP -> {
            message.checkAccess(Grant.WRITE_ACCESS)
            botService.removeGroup(chatId = chat.chatId, groupName = message.parseGroup())
            message.sendReply("Группа удалена.")
        }

        ADD_ALIAS -> {
            message.checkAccess(Grant.WRITE_ACCESS)
            val (groupName: GroupName, aliasName: GroupName) = message.parseGroupWithAlias()
            botService.addAlias(chatId = chat.chatId, groupName = groupName, aliasName = aliasName)
            message.sendReply("Синоним группы добавлен.")
        }

        REMOVE_ALIAS -> {
            message.checkAccess(Grant.WRITE_ACCESS)
            val (groupName: GroupName, aliasName: GroupName) = message.parseGroupWithAlias()
            botService.removeAlias(chatId = chat.chatId, groupName = groupName, aliasName = aliasName)
            message.sendReply("Синоним группы был удалён.")
        }

        ADD_MEMBERS -> {
            message.checkAccess(Grant.WRITE_ACCESS)
            val (groupName: GroupName, members: Set<Member>) = message.parseGroupWithMembers()
            botService.addMembers(chatId = chat.chatId, groupName = groupName, newMembers = members)
            message.sendReply(mapAddMembersResponse(groupName, members))
        }

        REMOVE_MEMBERS -> {
            message.checkAccess(Grant.WRITE_ACCESS)
            val (groupName: GroupName, members: Set<Member>) = message.parseGroupWithMembers()
            botService.removeMembers(chatId = chat.chatId, groupName = groupName, members = members)
            message.sendReply(mapRemoveMembersResponse(groupName, members))
        }

        ENABLE_ANARCHY -> {
            message.checkAccess(Grant.CHANGE_CHAT_SETTINGS)
            botService.enableAnarchy(chat.chatId)
            message.sendReply("Анархия включена. Все пользователи могут настраивать бота.")
        }

        DISABLE_ANARCHY -> {
            message.checkAccess(Grant.CHANGE_CHAT_SETTINGS)
            botService.disableAnarchy(chat.chatId)
            message.sendReply("Анархия выключена. Только администраторы могут настраивать бота.")
        }
    }

    /* Предварительные проверки */

    private fun Message.getAnyMessageEntities(): List<MessageEntity> = entities ?: captionEntities ?: emptyList()

    private fun MessageEntity.isBotCommand(): Boolean = offset == 0 && type == EntityType.BOTCOMMAND

    private fun Update.isCommandMessage(): Boolean =
        message?.getAnyMessageEntities()?.any { it.isBotCommand() } ?: false

    private fun Message.parseCommand(): Command? =
        Command.getByKey(getAnyMessageEntities().single { it.isBotCommand() }.text)

    private fun Message.isPrivateChat() = chat.type == CHAT_TYPE_PRIVATE

    private fun Message.isOwnerOrAdmin(): Boolean {
        val fromUserId = requireNotNull(from).id
        val members: List<ChatMember> = sendApiMethod(
            GetChatAdministrators().apply { chatId = chat.id.toString() }
        )
        for (member in members) {
            when {
                member is ChatMemberOwner && member.user.id == fromUserId -> return true
                member is ChatMemberAdministrator && member.user.id == fromUserId -> return true
            }
        }
        return false
    }

    private fun Message.isAnarchyEnabled(): Boolean = botService.isAnarchyEnabled(ChatId(chat.id))

    private fun Message.checkAccess(grant: Grant) {
        logger.info("checkAccess grant=[$grant]")
        when {
            grant == Grant.READ_ACCESS -> logger.info("Read access => no restrictions")
            isPrivateChat() -> logger.info("Private chat => no restrictions")
            isOwnerOrAdmin() -> logger.info("Owner or admin => no restrictions")
            isAnarchyEnabled() -> logger.info("No restriction when anarchy enabled")
            else -> throw BotReplyException.AuthorizationError("Access denied: grant=[$grant]")
        }
    }

    /* Парсинг входящих значений */

    private fun Message.parseByRegex(pattern: Regex, userConstraintDetails: Map<String, String>): MatchResult =
        pattern.matchEntire(text ?: caption) ?: throw BotReplyException.ValidationError(
            message = "Match error with pattern=[$pattern]",
            userMessage = "Ограничения:\n" +
                    userConstraintDetails.entries.joinToString(separator = ",\n") { "${it.key}=[${it.value}]" }
        )

    private fun Message.parseGroup(): GroupName {
        val matchResult = parseByRegex(
            pattern = BotConstraints.REGEX_CMD_GROUP,
            userConstraintDetails = mapOf("group" to BotConstraints.MESSAGE_FOR_GROUP)
        )
        return GroupName(requireNotNull(matchResult.groups["group"]?.value))
    }

    private fun Message.parseGroupWithTail(): GroupName {
        val matchResult = parseByRegex(
            pattern = BotConstraints.REGEX_CMD_GROUP_WITH_TAIL,
            userConstraintDetails = mapOf("group" to BotConstraints.MESSAGE_FOR_GROUP)
        )
        return GroupName(requireNotNull(matchResult.groups["group"]?.value))
    }

    private fun Message.parseGroupWithAlias(): Pair<GroupName, GroupName> {
        val matchResult: MatchResult = parseByRegex(
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

    private fun Message.parseGroupWithMembers(): Pair<GroupName, Set<Member>> {
        val matchResult: MatchResult = parseByRegex(
            pattern = BotConstraints.REGEX_CMD_GROUP_MEMBERS,
            userConstraintDetails = mapOf(
                "group" to BotConstraints.MESSAGE_FOR_GROUP,
                "username" to BotConstraints.MESSAGE_FOR_MEMBER
            )
        )
        val groupName = GroupName(requireNotNull(matchResult.groups["group"]?.value))
        val members: Set<Member> = (entities ?: emptyList())
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

    /* Маппинг ответов */

    private fun mapHelpResponse() = "Доступные команды:\n" +
            values().joinToString(separator = "\n") { command ->
                listOfNotNull(
                    command.keys.joinToString(separator = ", ") { "/$it" },
                    command.description
                ).joinToString(separator = " — ")
            }

    private fun mapCallResponse(groupName: GroupName, members: List<Member>): String =
        "Призываем участников группы [$groupName]:\n" +
                members.joinToString(separator = "\n") { "- ${it.memberName.value}" }

    private fun mapGroupsResponse(groups: List<GroupWithAliases>): String =
        "Вот такие группы существуют:\n" +
                groups.joinToString(separator = "\n") { "- ${it.groupName} (синонимы: ${it.aliasNames})" }

    private fun mapMembersResponse(groupName: GroupName, members: List<Member>): String =
        "Участники группы '$groupName':\n" +
                members.joinToString(separator = "\n") { "- ${it.memberName.value}" }

    // todo: смаппить правильно ссылку на пользователей без имени
    private fun mapAddMembersResponse(groupName: GroupName, members: Set<Member>): String =
        "Пользователи добавленные в группу '$groupName':\n" +
                members.joinToString(separator = "\n") { "- ${it.memberName.value}" }

    // todo: смаппить правильно ссылку на пользователей без имени
    private fun mapRemoveMembersResponse(groupName: GroupName, members: Set<Member>): String =
        "Пользователи удалённые из группы '$groupName':\n" +
                members.joinToString(separator = "\n") { "- ${it.memberName.value}" }

    private fun Message.sendReply(replyText: String) {
        execute(
            SendMessage().also { sendMessage ->
                sendMessage.chatId = chatId.toString()
                sendMessage.text = replyText
            }
        )
    }

    /* Обработка ошибок */

    private fun Message.catchException(block: () -> Unit) =
        suppressException {
            try {
                block()
            } catch (ex: BotReplyException) {
                when (ex) {
                    is BotReplyException.ValidationError -> {
                        logger.error("Validation error: ${ex.message}", ex)
                        sendReply("Ошибка валидации: ${ex.userMessage}")
                    }

                    is BotReplyException.NotFoundError -> {
                        logger.error("Not found error: ${ex.message}", ex)
                        sendReply(ex.userMessage)
                    }

                    is BotReplyException.IntegrityViolationError -> {
                        logger.error("Integrity violation error: ${ex.message}", ex)
                        sendReply(ex.userMessage)
                    }

                    is BotReplyException.AuthorizationError -> {
                        logger.error("Authorization error: ${ex.message}", ex)
                        sendReply("Действие запрещено! Обратитесь к администратору группы.")
                    }
                }
            } catch (ex: Exception) {
                logger.error("Unexpected error", ex)
                sendReply("Что-то пошло не так...")
            }
        }

    private fun suppressException(block: () -> Unit) {
        try {
            block()
        } catch (ex: Exception) {
            logger.error("Suppressed exception: ${ex::class.java}", ex)
        }
    }

    companion object {
        private const val CHAT_TYPE_PRIVATE = "private"
    }
}
