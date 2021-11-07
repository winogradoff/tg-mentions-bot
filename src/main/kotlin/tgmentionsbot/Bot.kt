package tgmentionsbot

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.telegram.telegrambots.bots.TelegramLongPollingBot
import org.telegram.telegrambots.meta.api.methods.commands.SetMyCommands
import org.telegram.telegrambots.meta.api.methods.groupadministration.GetChatAdministrators
import org.telegram.telegrambots.meta.api.methods.send.SendMessage
import org.telegram.telegrambots.meta.api.objects.Message
import org.telegram.telegrambots.meta.api.objects.Update
import org.telegram.telegrambots.meta.api.objects.chatmember.ChatMember
import org.telegram.telegrambots.meta.api.objects.commands.BotCommand
import tgmentionsbot.Command.*


@Component
class Bot(
    private val botProperties: BotProperties,
    private val botService: BotService,
    private val requestMapper: RequestMapper,
    private val responseMapper: ResponseMapper
) : TelegramLongPollingBot() {

    private val logger = LoggerFactory.getLogger(this.javaClass)

    override fun getBotToken(): String = botProperties.botToken
    override fun getBotUsername(): String = botProperties.botUsername

    override fun onRegister() {
        setBotCommands()
    }

    override fun onUpdateReceived(update: Update) {

        logger.debug("Update: $update")

        if (!requestMapper.isCommandMessage(update) || requestMapper.isForwardedMessage(update)) {
            return
        }

        val updateMessage: Message = update.message

        when (val command: Command? = requestMapper.parseCommand(updateMessage)) {
            null -> logger.warn("Unknown command: [$updateMessage]")
            else -> {
                logger.info("Command: [$command]")
                catchException(updateMessage) {
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
            checkAccess(message, Grant.READ_ACCESS)
            sendReply(message, responseMapper.toHelpResponse())
        }

        GROUPS -> {
            checkAccess(message, Grant.READ_ACCESS)
            sendReply(message, responseMapper.toGroupsResponse(botService.getGroups(chat.chatId)))
        }

        MEMBERS -> {
            checkAccess(message, Grant.READ_ACCESS)
            val groupName = requestMapper.parseGroup(message)
            val members = botService.getMembers(chatId = chat.chatId, groupName = groupName)
            sendReply(message, responseMapper.toMembersResponse(groupName, members))
        }

        CALL -> {
            checkAccess(message, Grant.READ_ACCESS)
            val groupName = requestMapper.parseGroupWithTail(message)
            val members = botService.getMembers(chatId = chat.chatId, groupName = groupName)
            sendReply(message, responseMapper.toCallResponse(groupName, members))
        }

        ADD_GROUP -> {
            checkAccess(message, Grant.WRITE_ACCESS)
            val groupName = requestMapper.parseGroup(message)
            botService.addGroup(chat = chat, groupName = groupName)
            sendReply(message, responseMapper.toAddGroupResponse(groupName))
        }

        REMOVE_GROUP -> {
            checkAccess(message, Grant.WRITE_ACCESS)
            botService.removeGroup(chatId = chat.chatId, groupName = requestMapper.parseGroup(message))
            sendReply(message, "Группа удалена.")
        }

        ADD_ALIAS -> {
            checkAccess(message, Grant.WRITE_ACCESS)
            val (groupName: GroupName, aliasName: GroupName) = requestMapper.parseGroupWithAlias(message)
            botService.addAlias(chatId = chat.chatId, groupName = groupName, aliasName = aliasName)
            sendReply(message, responseMapper.toAddAliasResponse(groupName, aliasName))
        }

        REMOVE_ALIAS -> {
            checkAccess(message, Grant.WRITE_ACCESS)
            val (groupName: GroupName, aliasName: GroupName) = requestMapper.parseGroupWithAlias(message)
            botService.removeAlias(chatId = chat.chatId, groupName = groupName, aliasName = aliasName)
            sendReply(message, "Синоним группы был удалён.")
        }

        ADD_MEMBERS -> {
            checkAccess(message, Grant.WRITE_ACCESS)
            val (groupName: GroupName, members: Set<Member>) = requestMapper.parseGroupWithMembers(message)
            botService.addMembers(chatId = chat.chatId, groupName = groupName, newMembers = members)
            sendReply(message, responseMapper.toAddMembersResponse(groupName, members))
        }

        REMOVE_MEMBERS -> {
            checkAccess(message, Grant.WRITE_ACCESS)
            val (groupName: GroupName, members: Set<Member>) = requestMapper.parseGroupWithMembers(message)
            botService.removeMembers(chatId = chat.chatId, groupName = groupName, members = members)
            sendReply(message, responseMapper.toRemoveMembersResponse(groupName, members))
        }

        ENABLE_ANARCHY -> {
            checkAccess(message, Grant.CHANGE_CHAT_SETTINGS)
            botService.enableAnarchy(chat.chatId)
            sendReply(message, "Анархия включена. Все пользователи могут настраивать бота.")
        }

        DISABLE_ANARCHY -> {
            checkAccess(message, Grant.CHANGE_CHAT_SETTINGS)
            botService.disableAnarchy(chat.chatId)
            sendReply(message, "Анархия выключена. Только администраторы могут настраивать бота.")
        }
    }

    private fun setBotCommands() {
        val setMyCommands = with(SetMyCommands.builder()) {
            for (command in Command.values()) {
                for (key in command.keys) {
                    command(
                        BotCommand.builder()
                            .command(key)
                            .description(command.description)
                            .build()
                    )
                }
            }
            build()
        }
        logger.info("Setting bot commands...")
        executeAsync(setMyCommands)
            .whenCompleteAsync { result: Boolean?, error: Throwable? ->
                logger.info("Setting bot commands is complete: result=[{}], error=[{}]", result, error)
            }
    }

    private fun sendReply(message: Message, replyText: String) {
        execute(
            SendMessage().also { sendMessage ->
                sendMessage.chatId = message.chatId.toString()
                sendMessage.replyToMessageId = message.messageId
                sendMessage.parseMode = PARSE_MODE
                sendMessage.text = replyText
            }
        )
    }

    private fun checkAccess(message: Message, grant: Grant) {
        logger.info("checkAccess grant=[$grant]")
        when {
            grant == Grant.READ_ACCESS ->
                logger.info("Read access => no restrictions")
            requestMapper.isPrivateChat(message) ->
                logger.info("Private chat => no restrictions")
            requestMapper.isOwnerOrAdmin(message, getChatAdministrators(message)) ->
                logger.info("Owner or admin => no restrictions")
            botService.isAnarchyEnabled(ChatId(message.chat.id)) ->
                logger.info("No restriction when anarchy enabled")
            else -> throw BotReplyException.AuthorizationError("Access denied: grant=[$grant]")
        }
    }

    private fun getChatAdministrators(message: Message): List<ChatMember> =
        sendApiMethod(
            GetChatAdministrators()
                .also { request -> request.chatId = message.chat.id.toString() }
        )

    private fun catchException(message: Message, block: () -> Unit) =
        suppressException {
            try {
                block()
            } catch (ex: BotReplyException) {
                when (ex) {
                    is BotReplyException.ValidationError -> {
                        logger.warn("Validation error: ${ex.message}")
                        sendReply(message, ex.userMessage)
                    }

                    is BotReplyException.NotFoundError -> {
                        logger.warn("Not found error: ${ex.message}")
                        sendReply(message, ex.userMessage)
                    }

                    is BotReplyException.IntegrityViolationError -> {
                        logger.warn("Integrity violation error: ${ex.message}")
                        sendReply(message, ex.userMessage)
                    }

                    is BotReplyException.AuthorizationError -> {
                        logger.warn("Authorization error: ${ex.message}")
                        sendReply(message, "Действие запрещено! Обратитесь к администратору группы.")
                    }
                }
            } catch (ex: Exception) {
                logger.error("Unexpected error", ex)
                sendReply(message, "Что-то пошло не так...")
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
        private const val PARSE_MODE = "HTML"
    }
}
