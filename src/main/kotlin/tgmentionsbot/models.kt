package tgmentionsbot

@JvmInline
value class ChatId(val value: Long) {
    override fun toString(): String = value.toString()
}

@JvmInline
value class GroupId(val value: Long) {
    override fun toString(): String = value.toString()
}

@JvmInline
value class GroupName(val value: String) {

    init {
        if (value.length > BotConstraints.MAX_GROUP_NAME_LENGTH) {
            throw BotReplyException.ValidationError(
                message = "Group name is too long:" +
                        " groupName=[$value]," +
                        " length=${value.length}," +
                        " MAX_GROUP_NAME_LENGTH=${BotConstraints.MAX_GROUP_NAME_LENGTH}",
                userMessage = "Слишком длинное имя группы." +
                        " Максимальная длина: ${BotConstraints.MAX_GROUP_NAME_LENGTH}"
            )
        }
    }

    override fun toString(): String = value
}

@JvmInline
value class AliasId(val value: Long) {
    override fun toString(): String = value.toString()
}

@JvmInline
value class UserId(val value: Long) {
    override fun toString(): String = value.toString()
}

@JvmInline
value class MemberId(val value: Long) {
    override fun toString(): String = value.toString()
}

@JvmInline
value class MemberName(val value: String) {

    init {
        if (value.length > BotConstraints.MAX_MEMBER_NAME_LENGTH) {
            throw BotReplyException.ValidationError(
                message = "Member name is too long:" +
                        " memberName=[$value]," +
                        " length=${value.length}," +
                        " MAX_MEMBER_NAME_LENGTH=${BotConstraints.MAX_MEMBER_NAME_LENGTH}",
                userMessage = "Слишком длинное имя пользователя." +
                        " Максимальная длина: ${BotConstraints.MAX_MEMBER_NAME_LENGTH}"
            )
        }
    }

    override fun toString(): String = value
}

data class Chat(
    val chatId: ChatId,
    val chatTitle: String? = null,
    val chatUserName: String? = null,
    val isAnarchyEnabled: Boolean? = null
)

data class Group(
    val groupId: GroupId
)

data class GroupAlias(
    val chatId: ChatId,
    val groupId: GroupId,
    val aliasName: GroupName,
    val aliasId: AliasId
)

data class GroupWithAliases(
    val groupName: GroupName,
    val aliasNames: List<GroupName>
)

data class Member(
    val memberName: MemberName,
    val memberId: MemberId? = null,
    val userId: UserId? = null
)

enum class Command(
    val keys: Set<String>,
    val description: String
) {
    HELP(keys = setOf("start", "help"), description = "справка по командам бота"),
    GROUPS(keys = setOf("groups"), description = "список групп"),
    MEMBERS(keys = setOf("members"), description = "список пользователей в группе"),
    CALL(keys = setOf("call", "c", "yo"), description = "позвать пользователей"),
    ADD_GROUP(keys = setOf("addGroup"), description = "добавить группу"),
    REMOVE_GROUP(keys = setOf("removeGroup"), description = "удалить группу"),
    ADD_ALIAS(keys = setOf("addAlias"), description = "добавить синоним группы"),
    REMOVE_ALIAS(keys = setOf("removeAlias"), description = "удалить синоним группы"),
    ADD_MEMBERS(keys = setOf("addMembers"), description = "добавить пользователей в группу"),
    REMOVE_MEMBERS(keys = setOf("removeMembers"), description = "удалить пользователей из конкретной группы"),
    PURGE_MEMBERS(keys = setOf("purgeMembers"), description = "удалить пользователей из всех групп чата"),
    ENABLE_ANARCHY(keys = setOf("enableAnarchy"), description = "всем доступны настройки"),
    DISABLE_ANARCHY(keys = setOf("disableAnarchy"), description = "только админам доступны настройки");

    companion object {
        private val lookup: Map<String, Command> =
            values()
                .asSequence()
                .flatMap { cmd -> cmd.keys.map { key -> key to cmd } }
                .associateBy(keySelector = { "/${it.first}" }, valueTransform = { it.second })

        fun getByKey(key: String): Command? = lookup[key]
    }
}

enum class Grant {
    READ_ACCESS,
    WRITE_ACCESS,
    CHANGE_CHAT_SETTINGS
}
