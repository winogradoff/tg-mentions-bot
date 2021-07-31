package tgmentionsbot

import org.slf4j.LoggerFactory

class BotService {

    private val logger = LoggerFactory.getLogger(this.javaClass)
    private val botRepository = BotRepository()

    fun getGroupAliases(chatId: ChatId): List<GroupAlias> {
        logger.info("Getting group aliases: chatId=[${chatId}]")
        return listOf(
                GroupAlias(chatId = chatId, groupId = GroupId(1), aliasName = "alias1", aliasId = AliasId(1)),
                GroupAlias(chatId = chatId, groupId = GroupId(1), aliasName = "alias2", aliasId = AliasId(2)),
                GroupAlias(chatId = chatId, groupId = GroupId(2), aliasName = "alias3", aliasId = AliasId(3))
        )
    }

    fun getMembers(chatId: ChatId, groupId: GroupId): List<Member> {
        logger.info("Getting members: chatId=[${chatId}], groupId=[${groupId}]")
        return listOf(
                Member(memberId = MemberId(1), userId = UserId(111), memberName = "m1"),
                Member(memberId = MemberId(2), userId = UserId(222), memberName = "m2"),
                Member(memberId = MemberId(3), userId = UserId(333), memberName = "m3")
        )
    }

    fun addGroup(chatId: ChatId, groupName: String) {
        logger.info("Adding group: chatId=[${chatId}], groupName=[$groupName]")
    }

    fun removeGroup(chatId: ChatId, groupName: String) {
        logger.info("Removing group: chatId=[${chatId}], groupName=[$groupName]")
    }

    fun addAlias(chatId: ChatId, aliasName: String) {
        logger.info("Adding alias: chatId=[${chatId}], aliasName=[$aliasName]")
    }

    fun removeAlias(chatId: ChatId, aliasName: String) {
        logger.info("Removing alias: chatId=[${chatId}], aliasName=[$aliasName]")
    }

    fun enableAnarchy(chatId: ChatId) {
        logger.info("Enabling anarchy: chatId=[${chatId}]")
    }

    fun disableAnarchy(chatId: ChatId) {
        logger.info("Disabling anarchy: chatId=[${chatId}]")
    }

    fun addMembers(chatId: ChatId, groupName: String, memberNames: List<String>) {
        logger.info("Adding members: chatId=[${chatId}], groupName=[$groupName], memberNames=[$memberNames]")
    }

    fun removeMembers(chatId: ChatId, groupName: String, memberNames: List<String>) {
        logger.info("Removing members: chatId=[${chatId}], groupName=[$groupName], memberNames=[$memberNames]")
    }
}
