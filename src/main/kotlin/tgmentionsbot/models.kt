package tgmentionsbot

@JvmInline
value class ChatId(val value: Long) {
    override fun toString(): String = value.toString()
}

@JvmInline
value class GroupId(val value: Int) {
    override fun toString(): String = value.toString()
}

@JvmInline
value class AliasId(val value: Int) {
    override fun toString(): String = value.toString()
}

@JvmInline
value class UserId(val value: Int) {
    override fun toString(): String = value.toString()
}

@JvmInline
value class MemberId(val value: Int) {
    override fun toString(): String = value.toString()
}

data class Chat(
        val chatId: ChatId,
        val chatTitle: String?,
        val chatUserName: String?,
        val isAnarchyEnabled: Boolean
)

data class Group(
        val groupId: GroupId
)

data class GroupAlias(
        val chatId: ChatId,
        val groupId: GroupId,
        val aliasName: String,
        val aliasId: AliasId?
)

data class Member(
        val memberName: String,
        val memberId: MemberId?,
        val userId: UserId
)

enum class Command(private val key: String) {
    START("start"),
    HELP("help"),
    GROUPS("groups"),
    MEMBERS("members"),
    CALL("call"),
    ADD_GROUP("add_group"),
    REMOVE_GROUP("remove_group"),
    ADD_ALIAS("add_alias"),
    REMOVE_ALIAS("remove_alias"),
    ADD_MEMBERS("add_members"),
    REMOVE_MEMBERS("remove_members"),
    ENABLE_ANARCHY("enable_anarchy"),
    DISABLE_ANARCHY("disable_anarchy");

    companion object {
        private val lookup: Map<String, Command> = values().associateBy { "/${it.key}" }
        fun getByKey(key: String): Command? = lookup[key]
    }
}
