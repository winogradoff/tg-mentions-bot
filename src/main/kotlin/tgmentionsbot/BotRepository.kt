package tgmentionsbot

import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.support.GeneratedKeyHolder
import org.springframework.jdbc.support.KeyHolder
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.sql.ResultSet


@Repository
class BotRepository(private val jdbcTemplate: NamedParameterJdbcTemplate) {

    private val logger = LoggerFactory.getLogger(this.javaClass)

    fun addChat(chat: Chat) {
        logger.info("Inserting chat: $chat")
        jdbcTemplate.update(
            """
                insert into chat (chat_id, chat_title, chat_username)
                values (:chat_id, :chat_title, :chat_username)
                on conflict(chat_id) do update
                set chat_title=:chat_title, chat_username=:chat_username
            """.trimIndent(),
            mapOf(
                "chat_id" to chat.chatId.value,
                "chat_title" to chat.chatTitle,
                "chat_username" to chat.chatUserName
            )
        ).checkUpdateCount(1)
    }

    @Transactional(propagation = Propagation.MANDATORY)
    fun getChatByIdForUpdate(chatId: ChatId) {
        logger.info("Getting chat for update by chatId=[$chatId]")
        jdbcTemplate.queryForObject(
            """
                select 1 from chat
                where chat_id = :chat_id
                for update
            """.trimIndent(),
            mapOf("chat_id" to chatId.value),
            Int::class.java
        )
    }

    fun getAliasesByChatId(chatId: ChatId): List<GroupAlias> {
        logger.info("Getting group aliases by chatId=[$chatId]")
        return jdbcTemplate.query(
            """
                select chat_id, group_id, alias_id, alias_name
                from chat_group_alias
                where chat_id = :chat_id
            """.trimIndent(),
            mapOf("chat_id" to chatId.value)
        ) { rs, _ ->
            GroupAlias(
                chatId = ChatId(rs.getLong("chat_id")),
                groupId = GroupId(rs.getLong("group_id")),
                aliasId = AliasId(rs.getLong("alias_id")),
                aliasName = GroupName(rs.getString("alias_name"))
            )
        }
    }

    fun getAliasesByGroupId(groupId: GroupId): List<GroupAlias> {
        logger.info("Getting group aliases by groupId=[$groupId]")
        return jdbcTemplate.query(
            """
                select chat_id, group_id, alias_id, alias_name
                from chat_group_alias
                where group_id = :group_id
            """.trimIndent(),
            mapOf("group_id" to groupId.value)
        ) { rs, _ ->
            GroupAlias(
                chatId = ChatId(rs.getLong("chat_id")),
                groupId = GroupId(rs.getLong("group_id")),
                aliasId = AliasId(rs.getLong("alias_id")),
                aliasName = GroupName(rs.getString("alias_name"))
            )
        }
    }

    fun getAliasByName(chatId: ChatId, aliasName: GroupName): GroupAlias? {
        logger.info("Getting group by chatId=[$chatId] and aliasName=[$aliasName]")
        return jdbcTemplate.query(
            """
                select chat_id, group_id, alias_id, alias_name
                from chat_group_alias
                where chat_id =:chat_id and alias_name = :alias_name
            """.trimIndent(),
            mapOf("chat_id" to chatId.value, "alias_name" to aliasName.value)
        ) { rs, _ ->
            GroupAlias(
                chatId = ChatId(rs.getLong("chat_id")),
                groupId = GroupId(rs.getLong("group_id")),
                aliasId = AliasId(rs.getLong("alias_id")),
                aliasName = GroupName(rs.getString("alias_name"))
            )
        }.singleOrNull()
    }

    fun addGroup(chatId: ChatId): GroupId {
        logger.info("Adding group for chatId=[$chatId]")
        val keyHolder: KeyHolder = GeneratedKeyHolder()
        jdbcTemplate.update(
            """
                insert into chat_group (chat_id)
                values (:chat_id) on conflict do nothing
                returning group_id
            """.trimIndent(),
            MapSqlParameterSource("chat_id", chatId.value),
            keyHolder
        ).checkUpdateCount(1)
        return GroupId(checkNotNull(keyHolder.key?.toLong()))
    }

    fun addAlias(chatId: ChatId, groupId: GroupId, aliasName: GroupName) {
        logger.info("Adding group alias: chatId=[$chatId], groupId=[$groupId], aliasName=[$aliasName]")
        jdbcTemplate.update(
            """
                insert into chat_group_alias (chat_id, group_id, alias_name)
                values (:chat_id, :group_id, :alias_name)
                on conflict do nothing
            """.trimIndent(),
            mapOf(
                "chat_id" to chatId.value,
                "group_id" to groupId.value,
                "alias_name" to aliasName.value
            )
        ).checkUpdateCount(1)
    }

    fun removeAliasById(aliasId: AliasId) {
        logger.info("Removing group alias by aliasId=[$aliasId]")
        jdbcTemplate.update(
            "delete from chat_group_alias where alias_id = :alias_id",
            mapOf("alias_id" to aliasId.value)
        ).checkUpdateCount(1)
    }

    fun getMembersByGroupId(groupId: GroupId): List<Member> {
        logger.info("Getting members by groupId=[$groupId]")
        return jdbcTemplate.query(
            """
                select member_name, user_id
                from member
                where group_id = :group_id
            """.trimIndent(),
            mapOf("group_id" to groupId.value)
        ) { rs, _ ->
            Member(
                memberName = MemberName(rs.getString("member_name")),
                userId = rs.getLongOrNull("user_id")?.let { UserId(it) }
            )
        }
    }

    fun getMembersByChatId(chatId: ChatId): List<Member> {
        logger.info("Getting members by chatId=[$chatId]")
        return jdbcTemplate.query(
            """
                select m.member_name, m.user_id
                from member m
                join chat_group cg on cg.group_id = m.group_id
                where cg.chat_id = :chat_id
            """.trimIndent(),
            mapOf("chat_id" to chatId.value)
        ) { rs, _ ->
            Member(
                memberName = MemberName(rs.getString("member_name")),
                userId = rs.getLongOrNull("user_id")?.let { UserId(it) }
            )
        }
    }

    fun removeGroupById(groupId: GroupId) {
        logger.info("Removing group by groupId=[$groupId]")
        jdbcTemplate.update(
            """
                delete from chat_group
                where group_id = :group_id
            """.trimIndent(),
            mapOf("group_id" to groupId.value)
        ).checkUpdateCount(1)
    }

    fun addMember(groupId: GroupId, member: Member) {
        logger.info("Adding member to group: groupId=[$groupId], member=[$member]")
        jdbcTemplate.update(
            """
                insert into member
                (group_id, member_name, user_id)
                values (:group_id, :member_name, :user_id)
                on conflict do nothing
            """.trimIndent(),
            mapOf(
                "group_id" to groupId.value,
                "member_name" to member.memberName.value,
                "user_id" to member.userId?.value
            )
        ).checkUpdateCount(1)
    }

    fun removeMemberFromGroupByName(groupId: GroupId, memberName: MemberName) {
        logger.info("Removing member from group: groupId=[$groupId], memberName=[$memberName]")
        jdbcTemplate.update(
            """
                delete from member
                where group_id = :group_id
                and member_name = :member_name
            """.trimIndent(),
            mapOf(
                "group_id" to groupId.value,
                "member_name" to memberName.value
            )
        ).checkUpdateCount(1)
    }

    fun removeMemberFromGroupByUserId(groupId: GroupId, userId: UserId) {
        logger.info("Removing member from group: groupId=[$groupId], userId=[$userId]")
        jdbcTemplate.update(
            """
                delete from member
                where group_id = :group_id
                and user_id = :user_id
            """.trimIndent(),
            mapOf(
                "group_id" to groupId.value,
                "user_id" to userId.value
            )
        ).checkUpdateCount(1)
    }

    fun removeMemberFromChatByName(chatId: ChatId, memberName: MemberName) {
        logger.info("Removing member from chat: chatId=[$chatId], memberName=[$memberName]")
        jdbcTemplate.update(
            """
                delete from member m
                where m.member_name = :member_name
                and m.group_id in (select cg.group_id from chat_group cg where cg.chat_id = :chat_id)
            """.trimIndent(),
            mapOf(
                "chat_id" to chatId.value,
                "member_name" to memberName.value
            )
        )
    }

    fun removeMemberFromChatByUserId(chatId: ChatId, userId: UserId) {
        logger.info("Removing member from chat: chatId=[$chatId], userId=[$userId]")
        jdbcTemplate.update(
            """
                delete from member m
                where m.user_id = :user_id
                and m.group_id in (select cg.group_id from chat_group cg where cg.chat_id = :chat_id)
            """.trimIndent(),
            mapOf(
                "chat_id" to chatId.value,
                "user_id" to userId.value
            )
        )
    }

    fun isAnarchyEnabled(chatId: ChatId): Boolean {
        logger.info("Getting actual anarchy status: chatId=[${chatId}]")
        val result: List<Boolean> = jdbcTemplate.query(
            """
                select is_anarchy_enabled
                from chat
                where chat_id = :chat_id
            """.trimIndent(),
            mapOf("chat_id" to chatId.value)
        ) { rs, _ -> rs.getBoolean("is_anarchy_enabled") }
        return result.isEmpty() || result.single()
    }

    fun setAnarchyStatus(chatId: ChatId, isAnarchyEnabled: Boolean) {
        logger.info("Setting anarchy status: chatId=[$chatId], isAnarchyEnabled=[$isAnarchyEnabled]")
        jdbcTemplate.update(
            """
                update chat
                set is_anarchy_enabled = :is_anarchy_enabled
                where chat_id = :chat_id
            """.trimIndent(),
            mapOf("chat_id" to chatId.value, "is_anarchy_enabled" to isAnarchyEnabled)
        ).checkUpdateCount(1)
    }

    private fun Int.checkUpdateCount(expected: Int) =
        check(this == expected) { "Wrong update count: actual=[$this], expected=[$expected]" }

    private fun ResultSet.getLongOrNull(column: String): Long? = getLong(column).takeUnless { wasNull() }
}
