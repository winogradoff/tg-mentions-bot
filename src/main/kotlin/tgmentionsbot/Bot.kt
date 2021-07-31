package tgmentionsbot

import org.slf4j.LoggerFactory
import org.telegram.telegrambots.bots.TelegramLongPollingBot
import org.telegram.telegrambots.meta.api.methods.send.SendMessage
import org.telegram.telegrambots.meta.api.objects.EntityType
import org.telegram.telegrambots.meta.api.objects.Message
import org.telegram.telegrambots.meta.api.objects.MessageEntity
import org.telegram.telegrambots.meta.api.objects.Update
import tgmentionsbot.Command.*

class Bot : TelegramLongPollingBot() {

    private val logger = LoggerFactory.getLogger(this.javaClass)
    private val config = BotConfig()
    private val service = BotService()

    override fun getBotToken(): String = config.botToken
    override fun getBotUsername(): String = config.botUsername

    override fun onUpdateReceived(update: Update) {
        logger.trace("Got update: $update")

        when {
            update.hasMessage() && update.message.isCommand -> with(update.message) {

                logger.info("Message text: [$text]")

                val chatId = ChatId(chatId)
                val commandMessageEntity: MessageEntity = getCommandMessageEntity()
                val command: Command? = Command.getByKey(commandMessageEntity.text)

                when (command) {
                    null -> {
                        logger.warn("Unknown command: [$commandMessageEntity]")
                        return
                    }
                    else -> logger.info("Command: [$command]")
                }

                when (command) {
                    START, HELP -> sendReply(getHelpMessage())
                    GROUPS -> sendReply(mapGroupsResponse(service.getGroupAliases(chatId)))
                    MEMBERS -> sendReply(mapMembersResponse(service.getMembers(chatId, GroupId(0))))
                    CALL -> sendReply(mapCallResponse(service.getMembers(chatId, GroupId(0))))

                    ADD_GROUP -> {
                        service.addGroup(chatId, "group")
                        sendReply("group added")
                    }
                    REMOVE_GROUP -> {
                        service.removeGroup(chatId, "group")
                        sendReply("group removed")
                    }

                    ADD_ALIAS -> {
                        service.addAlias(chatId, "alias")
                        sendReply("alias added")
                    }
                    REMOVE_ALIAS -> {
                        service.removeAlias(chatId, "alias")
                        sendReply("alias removed")
                    }

                    ADD_MEMBERS -> {
                        service.addMembers(chatId, "group", listOf("member1", "member2"))
                        sendReply("members added")
                    }
                    REMOVE_MEMBERS -> {
                        service.removeMembers(chatId, "group", listOf("member1", "member2"))
                        sendReply("members removed")
                    }

                    ENABLE_ANARCHY -> {
                        service.enableAnarchy(chatId)
                        sendReply("anarchy enabled")
                    }
                    DISABLE_ANARCHY -> {
                        service.disableAnarchy(chatId)
                        sendReply("anarchy disabled")
                    }
                }
            }
        }
    }

    private fun Message.getCommandMessageEntity(): MessageEntity =
            entities.single { EntityType.BOTCOMMAND == it.type }

    private fun Message.sendReply(replyText: String) {
        execute(
                SendMessage().also { sendMessage ->
                    sendMessage.chatId = chatId.toString()
                    sendMessage.text = replyText
                }
        )
    }

    private fun getHelpMessage(): String =
            "commands: ${Command.values().joinToString { it.name }}}"

    private fun mapCallResponse(members: List<Member>): String =
            "call members: ${members.joinToString { it.memberName }}"

    private fun mapGroupsResponse(groups: List<GroupAlias>): String =
            groups.groupBy(GroupAlias::groupId).values.joinToString {
                val groupName = it.first().aliasName
                val aliasNames = it.drop(1).map(GroupAlias::aliasName)
                "group: [$groupName], aliases: [$aliasNames]"
            }

    private fun mapMembersResponse(members: List<Member>): String =
            "members: ${members.joinToString { it.memberName }}"
}
