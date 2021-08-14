package tgmentionsbot

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate

@Service
class BotService(
    private val botRepository: BotRepository,
    private val transactionTemplate: TransactionTemplate
) {
    private val logger = LoggerFactory.getLogger(this.javaClass)

    fun getGroups(chatId: ChatId): List<GroupWithAliases> {
        logger.info("Getting group aliases: chatId=[${chatId}]")
        val groups = botRepository.getAliasesByChatId(chatId)
        checkGroupsExists(groups)
        val lookupByGroupId = groups.groupBy { it.groupId }
        return lookupByGroupId.keys
            .sortedBy { it.value }
            .map { groupId ->
                val aliases = lookupByGroupId.getValue(groupId).sortedBy { it.aliasId.value }
                GroupWithAliases(
                    groupName = aliases.first().aliasName,
                    aliasNames = aliases.drop(1).map { it.aliasName }
                )
            }
    }

    fun getMembers(chatId: ChatId, groupName: GroupName): List<Member> {
        logger.info("Getting members: chatId=[${chatId}], groupName=[${groupName}]")
        return checkNotNull(
            transactionTemplate.execute {
                val group = getGroupByNameOrThrow(chatId, groupName)
                botRepository.getMembersByGroupId(group.groupId)
            }
        )
    }

    fun addMembers(chatId: ChatId, groupName: GroupName, newMembers: Set<Member>) {
        logger.info("Adding members: chatId=[${chatId}], groupName=[$groupName], newMembers=[$newMembers]")
        if (newMembers.isEmpty()) {
            throw BotReplyException.ValidationError(
                message = "List of members is empty",
                userMessage = "Нужно указать хотя бы одного пользователя!"
            )
        }
        transactionTemplate.executeWithoutResult {
            val group = getGroupByNameOrThrow(chatId, groupName)
            val existingMembers = botRepository.getMembersByGroupId(group.groupId)
            if (existingMembers.size + newMembers.size > BotConstraints.MAX_MEMBERS_PER_GROUP) {
                throw BotReplyException.ValidationError(
                    message = "Too many members per group:" +
                            " existingMembers=[${existingMembers.size}]," +
                            " newMembers=[${newMembers.size}]," +
                            " MAX_MEMBERS_PER_GROUP=${BotConstraints.MAX_MEMBERS_PER_GROUP}",
                    userMessage = "Слишком много пользователей уже добавлено в группу!" +
                            " Текущее ограничение для одной группы: ${BotConstraints.MAX_MEMBERS_PER_GROUP}"
                )
            }
            newMembers.forEach { member ->
                botRepository.addMember(group.groupId, member)
            }
        }
    }

    fun removeMembers(chatId: ChatId, groupName: GroupName, members: Set<Member>) {
        logger.info("Removing members: chatId=[${chatId}], groupName=[$groupName], members=[$members]")
        if (members.isEmpty()) {
            throw BotReplyException.ValidationError(
                message = "List of members is empty",
                userMessage = "Нужно указать хотя бы одного пользователя!"
            )
        }
        transactionTemplate.executeWithoutResult {
            val group = getGroupByNameOrThrow(chatId, groupName)
            members.forEach { member ->
                botRepository.removeMemberByName(group.groupId, member.memberName)
            }
        }
    }

    fun addGroup(chat: Chat, groupName: GroupName) {
        logger.info("Adding group: chat=[${chat}], groupName=[$groupName]")
        transactionTemplate.executeWithoutResult {
            botRepository.addChat(chat)
            botRepository.getChatByIdForUpdate(chat.chatId)
            val groupAliases = botRepository.getAliasesByChatId(chat.chatId)
            verifyGroupDoesNotExistsYet(groupAliases, groupName)
            verifyNumberOfGroupsPerChat(groupAliases)
            val groupId = botRepository.addGroup(chat.chatId)
            botRepository.addAlias(chat.chatId, groupId, groupName)
        }
    }

    fun removeGroup(chatId: ChatId, groupName: GroupName) {
        logger.info("Removing group: chatId=[${chatId}], groupName=[$groupName]")
        transactionTemplate.executeWithoutResult {
            botRepository.getChatByIdForUpdate(chatId)
            val group = getGroupByNameOrThrow(chatId, groupName)
            val members = botRepository.getMembersByGroupId(group.groupId)
            if (members.isNotEmpty()) {
                throw BotReplyException.IntegrityViolationError(
                    message = "List of members is not empty",
                    userMessage = "Группу нельзя удалить, в ней есть пользователи!"
                )
            }
            val aliases = botRepository.getAliasesByGroupId(group.groupId)
            aliases.forEach { botRepository.removeAliasById(it.aliasId) }
            botRepository.removeGroupById(group.groupId)
        }
    }

    fun addAlias(chatId: ChatId, groupName: GroupName, aliasName: GroupName) {
        logger.info("Adding alias: chatId=[${chatId}], aliasName=[$aliasName]")
        transactionTemplate.executeWithoutResult {
            botRepository.getChatByIdForUpdate(chatId)
            val group = getGroupByNameOrThrow(chatId, groupName)
            val aliases = botRepository.getAliasesByGroupId(group.groupId)
            if (aliasName in aliases.map { it.aliasName }) {
                throw BotReplyException.ValidationError(
                    message = "This group alias already exists",
                    userMessage = "Такой синоним уже используется!"
                )
            }
            if (aliases.size >= BotConstraints.MAX_ALIASES_PER_GROUP) {
                throw BotReplyException.ValidationError(
                    message = "Too many aliases already exists:" +
                            " actualCount=[${aliases.size}]," +
                            " MAX_ALIASES_PER_GROUP=${BotConstraints.MAX_ALIASES_PER_GROUP}",
                    userMessage = "Нельзя добавить так много синонимов!" +
                            " Текущее ограничение для одной группы: ${BotConstraints.MAX_ALIASES_PER_GROUP}"
                )
            }
            botRepository.addAlias(chatId, group.groupId, aliasName)
        }
    }

    fun removeAlias(chatId: ChatId, groupName: GroupName, aliasName: GroupName) {
        logger.info("Removing alias: chatId=[${chatId}], groupName=[$groupName], aliasName=[$aliasName]")
        transactionTemplate.executeWithoutResult {
            botRepository.getChatByIdForUpdate(chatId)
            val group = getGroupByNameOrThrow(chatId, groupName)
            val aliasesByName = botRepository.getAliasesByGroupId(group.groupId).associateBy { it.aliasName }
            val aliasForDelete = aliasesByName[aliasName]
                ?: throw BotReplyException.ValidationError(
                    message = "Alias [$aliasName] is not found for group [$groupName]",
                    userMessage = "Синоним '$aliasName' не найден для группы '$groupName'!"
                )
            if (aliasesByName.size == 1) {
                throw BotReplyException.ValidationError(
                    message = "Can't delete single group alias",
                    userMessage = "Нельзя удалить единственное название группы!"
                )
            }
            botRepository.removeAliasById(aliasForDelete.aliasId)
        }
    }

    fun enableAnarchy(chatId: ChatId) {
        logger.info("Enabling anarchy: chatId=[${chatId}]")
        botRepository.setAnarchyStatus(chatId, isAnarchyEnabled = true)
    }

    fun disableAnarchy(chatId: ChatId) {
        logger.info("Disabling anarchy: chatId=[${chatId}]")
        botRepository.setAnarchyStatus(chatId, isAnarchyEnabled = false)
    }

    fun isAnarchyEnabled(chatId: ChatId): Boolean {
        logger.info("Getting actual anarchy status: chatId=[${chatId}]")
        return botRepository.isAnarchyEnabled(chatId)
    }

    private fun verifyNumberOfGroupsPerChat(groupAliases: List<GroupAlias>) {
        val countOfGroupsInChat = groupAliases.asSequence().distinctBy { it.groupId }.count()
        check(countOfGroupsInChat < BotConstraints.MAX_GROUPS_PER_CHAT) { "Too much groups" }
    }

    private fun verifyGroupDoesNotExistsYet(groupAliases: List<GroupAlias>, groupName: GroupName) =
        check(groupAliases.none { it.aliasName == groupName }) { "Group name already exists" }

    private fun getGroupByNameOrThrow(chatId: ChatId, groupName: GroupName): GroupAlias =
        botRepository.getAliasByName(chatId, groupName)
            ?: throw BotReplyException.NotFoundError(
                message = "Group not found: chatId=[${chatId}], groupName=[$groupName]",
                userMessage = "Группа '$groupName' не найдена!"
            )

    private fun checkGroupsExists(groups: List<*>) {
        if (groups.isEmpty()) {
            throw BotReplyException.NotFoundError(
                message = "Group list is empty",
                userMessage = "Нет ни одной группы."
            )
        }
    }
}
