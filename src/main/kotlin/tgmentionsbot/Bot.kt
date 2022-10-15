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

        when (val command: Command? = requestMapper.parseCommand(updateMessage, me.userName)) {
            null -> logger.warn("Unknown command: [$updateMessage]")
            else -> {
                logger.info("Command: [$command]")
                routeCommand(command = command, message = updateMessage)
            }
        }
    }

    private fun routeCommand(command: Command, message: Message) = catchException(message) {
        val chat = Chat(
            chatId = ChatId(message.chatId),
            chatTitle = message.chat.title,
            chatUserName = message.chat.userName
        )

        when (command) {

            HELP -> {
                checkAccess(message, Grant.READ_ACCESS)
                sendReply(message, responseMapper.toHelpMessage(command))
            }

            GROUPS -> handleCommand(
                command = command,
                message = message,
                grant = Grant.READ_ACCESS,
                handler = {
                    sendReply(message, responseMapper.toGroupsResponse(botService.getGroups(chat.chatId)))
                }
            )

            MEMBERS -> handleCommand(
                command = command,
                message = message,
                grant = Grant.READ_ACCESS,
                handler = {
                    val groupName = requestMapper.parseGroup(message)
                    val members = botService.getGroupMembers(chatId = chat.chatId, groupName = groupName)
                    sendReply(message, responseMapper.toMembersResponse(groupName, members))
                }
            )

            CALL -> handleCommand(
                command = command,
                message = message,
                grant = Grant.READ_ACCESS,
                handler = {
                    val groupName = requestMapper.parseGroupWithTail(message)
                    val members = botService.getGroupMembers(chatId = chat.chatId, groupName = groupName)
                    sendReply(message, responseMapper.toCallResponse(groupName, members))
                }
            )

            HERE -> handleCommand(
                command = command,
                message = message,
                grant = Grant.READ_ACCESS,
                handler = {
                    val members = botService.getChatMembers(chat.chatId)
                    sendReply(message, responseMapper.toHereResponse(members))
                }
            )

            ADD_GROUP -> handleCommand(
                command = command,
                message = message,
                grant = Grant.WRITE_ACCESS,
                handler = {
                    val groupName = requestMapper.parseGroup(message)
                    botService.addGroup(chat = chat, groupName = groupName)
                    sendReply(message, responseMapper.toAddGroupResponse(groupName))
                }
            )

            REMOVE_GROUP -> handleCommand(
                command = command,
                message = message,
                grant = Grant.WRITE_ACCESS,
                handler = {
                    botService.removeGroup(
                        chatId = chat.chatId,
                        groupName = requestMapper.parseGroup(message),
                        force = false
                    )
                    sendReply(message, "Группа удалена.")
                }
            )

            REMOVE_GROUP_FORCE -> handleCommand(
                command = command,
                message = message,
                grant = Grant.WRITE_ACCESS,
                handler = {
                    botService.removeGroup(
                        chatId = chat.chatId,
                        groupName = requestMapper.parseGroup(message),
                        force = true
                    )
                    sendReply(message, "Группа удалена.")
                }
            )

            ADD_ALIAS -> handleCommand(
                command = command,
                message = message,
                grant = Grant.WRITE_ACCESS,
                handler = {
                    val (groupName: GroupName, aliasName: GroupName) = requestMapper.parseGroupWithAlias(message)
                    botService.addAlias(chatId = chat.chatId, groupName = groupName, aliasName = aliasName)
                    sendReply(message, responseMapper.toAddAliasResponse(groupName, aliasName))
                }
            )

            REMOVE_ALIAS -> handleCommand(
                command = command,
                message = message,
                grant = Grant.WRITE_ACCESS,
                handler = {
                    val aliasName = requestMapper.parseGroup(message)
                    botService.removeAlias(chatId = chat.chatId, aliasName = aliasName)
                    sendReply(message, responseMapper.toRemoveAliasResponse(aliasName))
                }
            )

            ADD_MEMBERS -> handleCommand(
                command = command,
                message = message,
                grant = Grant.WRITE_ACCESS,
                handler = {
                    val (groupName: GroupName, members: Set<Member>) = requestMapper.parseGroupWithMembers(message)
                    botService.addMembers(chat = chat, groupName = groupName, newMembers = members)
                    sendReply(message, responseMapper.toAddMembersResponse(groupName, members))
                }
            )

            REMOVE_MEMBERS -> handleCommand(
                command = command,
                message = message,
                grant = Grant.WRITE_ACCESS,
                handler = {
                    val (groupName: GroupName, members: Set<Member>) = requestMapper.parseGroupWithMembers(message)
                    botService.removeMembersFromGroup(chatId = chat.chatId, groupName = groupName, members = members)
                    sendReply(message, responseMapper.toRemoveMembersResponse(groupName, members))
                }
            )

            PURGE_MEMBERS -> handleCommand(
                command = command,
                message = message,
                grant = Grant.WRITE_ACCESS,
                handler = {
                    val members: Set<Member> = requestMapper.parseMembers(message)
                    botService.removeMembersFromChat(chatId = chat.chatId, members = members)
                    sendReply(message, responseMapper.toPurgeMembersResponse(members))
                }
            )

            MUTE_MEMBERS -> handleCommand(
                command = command,
                message = message,
                grant = Grant.WRITE_ACCESS,
                handler = {
                    val members: Set<Member> = requestMapper.parseMembers(message)
                    botService.muteMembers(chatId = chat.chatId, members = members)
                    sendReply(message, responseMapper.toMuteMembersResponse(members))
                }
            )

            UNMUTE_MEMBERS -> handleCommand(
                command = command,
                message = message,
                grant = Grant.WRITE_ACCESS,
                handler = {
                    val members: Set<Member> = requestMapper.parseMembers(message)
                    botService.unmuteMembers(chatId = chat.chatId, members = members)
                    sendReply(message, responseMapper.toUnmuteMembersResponse(members))
                }
            )

            ENABLE_ANARCHY -> handleCommand(
                command = command,
                message = message,
                grant = Grant.CHANGE_CHAT_SETTINGS,
                handler = {
                    botService.enableAnarchy(chat)
                    sendReply(message, "Анархия включена. Все пользователи могут настраивать бота.")
                }
            )

            DISABLE_ANARCHY -> handleCommand(
                command = command,
                message = message,
                grant = Grant.CHANGE_CHAT_SETTINGS,
                handler = {
                    botService.disableAnarchy(chat)
                    sendReply(message, "Анархия выключена. Только администраторы могут настраивать бота.")
                }
            )

        }.exhaustive
    }

    private fun handleCommand(command: Command, message: Message, grant: Grant, handler: () -> Unit) {
        checkAccess(message, grant)
        try {
            handler()
        } catch (ex: BotParseException) {
            sendReply(message, responseMapper.toHelpMessage(command))
        }
    }

    private fun setBotCommands() {
        val setMyCommands = with(SetMyCommands.builder()) {
            for (command in Command.values()) {
                command(
                    BotCommand.builder()
                        .command(command.firstKey())
                        .description(command.description)
                        .build()
                )
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

        fun isPrivateChat() = requestMapper.isPrivateChat(message)
        fun isOwnerOrAdmin() = requestMapper.isOwnerOrAdmin(message, getChatAdministrators(message))
        fun isAnarchyEnabled() = botService.isAnarchyEnabled(ChatId(message.chat.id))
        fun throwAuthError(): Nothing = throw BotReplyException.AuthorizationError("Access denied: grant=[$grant]")

        when (grant) {

            Grant.READ_ACCESS -> logger.info("Read access => no restrictions")

            Grant.WRITE_ACCESS -> when {
                isPrivateChat() -> logger.info("Private chat => no restrictions")
                isOwnerOrAdmin() -> logger.info("Owner or admin => no restrictions")
                isAnarchyEnabled() -> logger.info("No restriction when anarchy enabled")
                else -> throwAuthError()
            }

            Grant.CHANGE_CHAT_SETTINGS -> when {
                isPrivateChat() -> logger.info("Private chat => no restrictions")
                isOwnerOrAdmin() -> logger.info("Owner or admin => no restrictions")
                else -> throwAuthError()
            }

        }.exhaustive
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
                sendReply(
                    message = message,
                    replyText = when (ex) {
                        is BotReplyException.ValidationError -> {
                            logger.warn("Validation error: ${ex.message}")
                            ex.userMessage
                        }

                        is BotReplyException.NotFoundError -> {
                            logger.warn("Not found error: ${ex.message}")
                            ex.userMessage
                        }

                        is BotReplyException.IntegrityViolationError -> {
                            logger.warn("Integrity violation error: ${ex.message}")
                            ex.userMessage
                        }

                        is BotReplyException.AuthorizationError -> {
                            logger.warn("Authorization error: ${ex.message}")
                            "Действие запрещено! Обратитесь к администратору группы."
                        }
                    }
                )
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
