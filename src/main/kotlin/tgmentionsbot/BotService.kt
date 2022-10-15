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
            .map { groupId ->
                val aliases = lookupByGroupId.getValue(groupId).sortedBy { it.aliasId.value }
                GroupWithAliases(
                    groupName = aliases.first().aliasName,
                    aliasNames = aliases.drop(1).map { it.aliasName }
                )
            }
            .sortedBy { it.groupName.value }
    }

    fun getGroupMembers(chatId: ChatId, groupName: GroupName): List<Member> {
        logger.info("Getting group members: chatId=[${chatId}], groupName=[${groupName}]")
        return when (groupName.value) {
            in ALL_MEMBERS_GROUPS -> botRepository.getMembersByChatId(chatId)
                .distinctBy { listOf(it.memberName, it.userId) }

            else -> {
                getGroupByNameOrThrow(chatId, groupName)
                    .let { botRepository.getMembersByGroupId(it.groupId) }
            }
        }.sortedBy { it.memberName.value }
    }

    fun getChatMembers(chatId: ChatId): List<Member> {
        logger.info("Getting all chat members: chatId=[${chatId}]")
        return botRepository.getMembersByChatId(chatId)
            .distinctBy { listOf(it.memberName, it.userId) }
            .sortedBy { it.memberName.value }
    }

    fun addMembers(chatId: ChatId, groupName: GroupName, newMembers: Set<Member>) {
        logger.info("Adding members: chatId=[${chatId}], groupName=[$groupName], newMembers=[$newMembers]")
        checkMembersNotEmpty(newMembers)
        transactionTemplate.executeWithoutResult {
            botRepository.getChatByIdForUpdate(chatId)
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
                verifyMemberDoesNotExistsYet(existingMembers, member)
                botRepository.addMember(group.groupId, member)
            }
        }
    }

    fun removeMembersFromGroup(chatId: ChatId, groupName: GroupName, members: Set<Member>) {
        logger.info("Removing members: chatId=[${chatId}], groupName=[$groupName], members=[$members]")
        checkMembersNotEmpty(members)
        transactionTemplate.executeWithoutResult {
            val group = getGroupByNameOrThrow(chatId, groupName)
            members.forEach { member ->
                when {
                    member.userId != null -> botRepository.removeMemberFromGroupByUserId(group.groupId, member.userId)
                    else -> botRepository.removeMemberFromGroupByName(group.groupId, member.memberName)
                }
            }
        }
    }

    fun removeMembersFromChat(chatId: ChatId, members: Set<Member>) {
        logger.info("Purging members: chatId=[${chatId}], members=[$members]")
        checkMembersNotEmpty(members)
        transactionTemplate.executeWithoutResult {
            members.forEach { member ->
                when {
                    member.userId != null -> botRepository.removeMemberFromChatByUserId(chatId, member.userId)
                    else -> botRepository.removeMemberFromChatByName(chatId, member.memberName)
                }
            }
        }
    }

    fun muteMembers(chatId: ChatId, members: Set<Member>) {
        logger.info("Muting members: chatId=[${chatId}], members=[$members]")
        checkMembersNotEmpty(members)
        transactionTemplate.executeWithoutResult {
            members.forEach { member ->
                when {
                    member.userId != null -> botRepository.muteMemberByUserId(chatId, member.userId)
                    else -> botRepository.muteMemberByName(chatId, member.memberName)
                }
            }
        }
    }

    fun unmuteMembers(chatId: ChatId, members: Set<Member>) {
        logger.info("Unmuting members: chatId=[${chatId}], members=[$members]")
        checkMembersNotEmpty(members)
        transactionTemplate.executeWithoutResult {
            members.forEach { member ->
                when {
                    member.userId != null -> botRepository.unmuteMemberByUserId(chatId, member.userId)
                    else -> botRepository.unmuteMemberByName(chatId, member.memberName)
                }
            }
        }
    }

    fun addGroup(chat: Chat, groupName: GroupName) {
        logger.info("Adding group: chat=[${chat}], groupName=[$groupName]")
        transactionTemplate.executeWithoutResult {
            botRepository.addChat(chat)
            botRepository.getChatByIdForUpdate(chat.chatId)
            val groupAliases = botRepository.getAliasesByChatId(chat.chatId)
            verifyNumberOfGroupsPerChat(groupAliases)
            verifyGroupDoesNotExistsYet(groupAliases, groupName)
            val groupId = botRepository.addGroup(chat.chatId)
            botRepository.addAlias(chat.chatId, groupId, groupName)
        }
    }

    fun removeGroup(chatId: ChatId, groupName: GroupName, force: Boolean) {
        logger.info("Removing group: chatId=[${chatId}], groupName=[$groupName], force=[$force]")
        transactionTemplate.executeWithoutResult {
            botRepository.getChatByIdForUpdate(chatId)
            val group = getGroupByNameOrThrow(chatId, groupName)
            val members = botRepository.getMembersByGroupId(group.groupId)
            when {
                !force && members.isNotEmpty() -> throw BotReplyException.IntegrityViolationError(
                    message = "List of members is not empty",
                    userMessage = "Группу нельзя удалить, в ней есть пользователи!"
                )

                else -> members.forEach { botRepository.removeMemberFromGroupByName(group.groupId, it.memberName) }
            }
            botRepository.getAliasesByGroupId(group.groupId)
                .forEach { botRepository.removeAliasById(it.aliasId) }
            botRepository.removeGroupById(group.groupId)
        }
    }

    fun addAlias(chatId: ChatId, groupName: GroupName, aliasName: GroupName) {
        logger.info("Adding alias: chatId=[${chatId}], aliasName=[$aliasName]")
        transactionTemplate.executeWithoutResult {
            botRepository.getChatByIdForUpdate(chatId)
            val group = getGroupByNameOrThrow(chatId, groupName)
            val chatAliases = botRepository.getAliasesByChatId(chatId)
            if (aliasName in chatAliases.map { it.aliasName }) {
                throw BotReplyException.ValidationError(
                    message = "This group alias already exists",
                    userMessage = "Такой синоним уже используется!"
                )
            }
            val groupAliases = botRepository.getAliasesByGroupId(group.groupId)
            if (groupAliases.size >= BotConstraints.MAX_ALIASES_PER_GROUP) {
                throw BotReplyException.ValidationError(
                    message = "Too many aliases already exists:" +
                            " actualCount=[${groupAliases.size}]," +
                            " MAX_ALIASES_PER_GROUP=${BotConstraints.MAX_ALIASES_PER_GROUP}",
                    userMessage = "Нельзя добавить так много синонимов!" +
                            " Текущее ограничение для одной группы: ${BotConstraints.MAX_ALIASES_PER_GROUP}"
                )
            }
            botRepository.addAlias(chatId, group.groupId, aliasName)
        }
    }

    fun removeAlias(chatId: ChatId, aliasName: GroupName) {
        logger.info("Removing alias: chatId=[${chatId}], aliasName=[$aliasName]")
        transactionTemplate.executeWithoutResult {
            botRepository.getChatByIdForUpdate(chatId)
            val group = getGroupByNameOrThrow(chatId, aliasName)
            val aliasesByName = botRepository.getAliasesByGroupId(group.groupId).associateBy { it.aliasName }
            val aliasForDelete = aliasesByName.getValue(aliasName)
            if (aliasesByName.size == 1) {
                throw BotReplyException.ValidationError(
                    message = "Can't delete single group alias",
                    userMessage = "Нельзя удалить единственное название группы!"
                )
            }
            botRepository.removeAliasById(aliasForDelete.aliasId)
        }
    }

    fun enableAnarchy(chat: Chat) {
        logger.info("Enabling anarchy: chatId=[${chat.chatId}]")
        transactionTemplate.executeWithoutResult {
            botRepository.addChat(chat)
            botRepository.getChatByIdForUpdate(chat.chatId)
            botRepository.setAnarchyStatus(chat.chatId, isAnarchyEnabled = true)
        }
    }

    fun disableAnarchy(chat: Chat) {
        logger.info("Disabling anarchy: chatId=[${chat.chatId}]")
        transactionTemplate.executeWithoutResult {
            botRepository.addChat(chat)
            botRepository.getChatByIdForUpdate(chat.chatId)
            botRepository.setAnarchyStatus(chat.chatId, isAnarchyEnabled = false)
        }
    }

    fun isAnarchyEnabled(chatId: ChatId): Boolean {
        logger.info("Getting actual anarchy status: chatId=[${chatId}]")
        return botRepository.isAnarchyEnabled(chatId)
    }

    private fun verifyNumberOfGroupsPerChat(groupAliases: List<GroupAlias>) {
        val countOfGroupsInChat = groupAliases.asSequence().distinctBy { it.groupId }.count()
        val maxGroupsPerChat = BotConstraints.MAX_GROUPS_PER_CHAT
        if (countOfGroupsInChat >= maxGroupsPerChat) {
            throw BotReplyException.IntegrityViolationError(
                message = "Too much groups, maxGroupsPerChat=[$maxGroupsPerChat]",
                userMessage = "Слишком много групп уже создано! Текущее ограничение для чата: $maxGroupsPerChat"
            )
        }
    }

    private fun verifyGroupDoesNotExistsYet(groupAliases: List<GroupAlias>, groupName: GroupName) {
        if (groupAliases.any { it.aliasName == groupName }) {
            throw BotReplyException.IntegrityViolationError(
                message = "Group [$groupName] already exists!",
                userMessage = "Такая группа уже существует!"
            )
        }
    }

    private fun verifyMemberDoesNotExistsYet(oldMembers: List<Member>, newMember: Member) {
        for (oldMember in oldMembers) {
            val isSameMember: Boolean = when {
                oldMember.userId != null && newMember.userId != null -> oldMember.userId == newMember.userId
                else -> oldMember.memberName == newMember.memberName
            }
            if (isSameMember) {
                throw BotReplyException.IntegrityViolationError(
                    message = "Member already exists: newMember=[$newMember], oldMember=[$oldMember]",
                    userMessage = "Такой пользователь уже существует!"
                )
            }
        }
    }

    private fun getGroupByNameOrThrow(chatId: ChatId, groupName: GroupName): GroupAlias =
        botRepository.getAliasByName(chatId, groupName)
            ?: throw BotReplyException.NotFoundError(
                message = "Group not found: chatId=[${chatId}], groupName=[$groupName]",
                userMessage = "Такая группа не найдена!"
            )

    private fun checkGroupsExists(groups: List<*>) {
        if (groups.isEmpty()) {
            throw BotReplyException.NotFoundError(
                message = "Group list is empty",
                userMessage = "Нет ни одной группы."
            )
        }
    }

    private fun checkMembersNotEmpty(members: Set<Member>) {
        if (members.isEmpty()) {
            throw BotReplyException.ValidationError(
                message = "List of members is empty",
                userMessage = "Нужно указать хотя бы одного пользователя!"
            )
        }
    }

    companion object {
        private val ALL_MEMBERS_GROUPS: Set<String> = setOf(
            "all", "все",
            "everyone"
        )
    }
}
