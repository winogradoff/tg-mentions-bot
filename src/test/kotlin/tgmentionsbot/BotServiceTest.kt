package tgmentionsbot

import assertk.assertThat
import assertk.assertions.*
import io.mockk.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.support.TransactionCallback
import org.springframework.transaction.support.TransactionTemplate
import java.util.function.Consumer


@Suppress("ClassName", "UNCHECKED_CAST")
internal class BotServiceTest {

    private val botRepository: BotRepository = mockk(relaxUnitFun = true)

    private val transactionTemplate: TransactionTemplate = mockk {
        every { execute(any<TransactionCallback<*>>()) } answers {
            (it.invocation.args.first() as TransactionCallback<*>).doInTransaction(mockk())
        }
        every { executeWithoutResult(any<Consumer<TransactionStatus>>()) } answers {
            (it.invocation.args.first() as Consumer<TransactionStatus>).accept(mockk())
        }
    }

    private val botService = BotService(
        botRepository = botRepository,
        transactionTemplate = transactionTemplate
    )

    private val chatId = ChatId(123)

    @Nested
    inner class `getGroups - tests` {

        @Test
        fun positive() {
            // given
            val groupId1 = GroupId(456)
            val groupId2 = GroupId(789)
            val groupAlias1 = GroupAlias(
                chatId = chatId,
                groupId = groupId1,
                aliasName = GroupName("qwe1"),
                aliasId = AliasId(111)
            )
            val groupAlias2 = GroupAlias(
                chatId = chatId,
                groupId = groupId1,
                aliasName = GroupName("qwe2"),
                aliasId = AliasId(222)
            )
            val groupAlias3 = GroupAlias(
                chatId = chatId,
                groupId = groupId2,
                aliasName = GroupName("asd1"),
                aliasId = AliasId(333)
            )

            every { botRepository.getAliasesByChatId(chatId) } returns listOf(groupAlias1, groupAlias2, groupAlias3)

            // when
            val groups = botService.getGroups(chatId)

            // then
            assertThat(groups).hasSize(2)
            assertThat(groups.singleOrNull { it.groupName == groupAlias1.aliasName })
                .isNotNull().transform { it.aliasNames }.containsExactlyInAnyOrder(groupAlias2.aliasName)
            assertThat(groups.singleOrNull { it.groupName == groupAlias3.aliasName })
                .isNotNull().transform { it.aliasNames }.isEmpty()
        }
    }

    @Nested
    inner class `getGroupMembers - tests` {

        @Test
        fun positive() {
            // given
            val groupName = GroupName("qwe")
            val groupId = GroupId(123)
            val groupAlias = GroupAlias(
                chatId = chatId,
                groupId = groupId,
                aliasName = groupName,
                aliasId = AliasId(456)
            )
            val member1 = Member(memberName = MemberName("aaa"), userId = UserId(111))
            val member2 = Member(memberName = MemberName("bbb"), userId = UserId(222))

            every { botRepository.getAliasByName(chatId = chatId, aliasName = groupName) } returns groupAlias
            every { botRepository.getMembersByGroupId(groupId = groupId) } returns listOf(member1, member2)

            // when
            val members = botService.getGroupMembers(chatId = chatId, groupName = groupName)

            // then
            assertThat(members).containsExactlyInAnyOrder(member1, member2)
        }

        @Test
        fun `positive - group=all`() {
            // given
            val groupName = GroupName("all")
            val groupId = GroupId(123)
            val groupAlias = GroupAlias(
                chatId = chatId,
                groupId = groupId,
                aliasName = groupName,
                aliasId = AliasId(456)
            )
            val member1 = Member(memberName = MemberName("aaa"), userId = UserId(111))
            val member2 = Member(memberName = MemberName("bbb"), userId = UserId(222))
            val member3 = member2.copy()

            every { botRepository.getAliasByName(chatId = chatId, aliasName = groupName) } returns groupAlias
            every { botRepository.getMembersByGroupId(groupId = groupId) } returns listOf(member1)
            every { botRepository.getMembersByChatId(chatId = chatId) } returns listOf(member1, member2, member3)

            // when
            val members = botService.getGroupMembers(chatId = chatId, groupName = groupName)

            // then
            assertThat(members).containsExactlyInAnyOrder(member1, member2)
        }
    }

    @Nested
    inner class `getChatMembers - tests` {

        @Test
        fun positive() {
            // given
            val member1 = Member(memberName = MemberName("aaa"), userId = UserId(111))
            val member2 = Member(memberName = MemberName("bbb"), userId = UserId(222))
            val member3 = member2.copy()
            every { botRepository.getMembersByChatId(chatId) } returns listOf(member1, member2, member3)

            // when
            val members = botService.getChatMembers(chatId)

            // then
            assertThat(members).containsExactlyInAnyOrder(member1, member2)
        }
    }

    @Nested
    inner class `addGroup - tests` {

        @Test
        fun positive() {
            // given
            val chat = Chat(
                chatId = chatId,
                chatTitle = "chat-title",
                chatUserName = "chat-name",
                isAnarchyEnabled = null
            )
            val groupId = GroupId(123)
            val groupName = GroupName("qwe")

            every { botRepository.addChat(any()) } just runs
            every { botRepository.getChatByIdForUpdate(chatId) } just runs
            every { botRepository.getAliasesByChatId(chatId) } returns emptyList()
            every { botRepository.addGroup(chatId) } returns groupId

            // when
            botService.addGroup(chat = chat, groupName = groupName)

            // then
            verify { botRepository.addChat(chat) }
            verify { botRepository.addGroup(chatId) }
            verify { botRepository.addAlias(chatId, groupId, groupName) }
        }
    }

    @Nested
    inner class `removeGroup - tests` {

        private val groupId = GroupId(123)
        private val groupName = GroupName("qwe")
        private val group = GroupAlias(
            chatId = chatId,
            groupId = groupId,
            aliasId = AliasId(111),
            aliasName = groupName
        )
        private val aliasId1 = AliasId(1)
        private val aliasId2 = AliasId(2)
        private val memberName1 = MemberName("aaa")
        private val memberName2 = MemberName("bbb")

        @BeforeEach
        fun beforeEach() {
            every { botRepository.getAliasByName(chatId, groupName) } returns group
            every { botRepository.getMembersByGroupId(groupId) } returns emptyList()

            every { botRepository.getAliasesByGroupId(groupId) } returns listOf(
                GroupAlias(chatId = chatId, aliasId = AliasId(1), groupId = groupId, aliasName = GroupName("aaa")),
                GroupAlias(chatId = chatId, aliasId = AliasId(2), groupId = groupId, aliasName = GroupName("bbb")),
            )
        }

        @Test
        fun `positive - no force - empty group`() {
            // when
            botService.removeGroup(chatId = chatId, groupName = groupName, force = false)

            // then
            verify { botRepository.removeGroupById(groupId) }
            verify { botRepository.removeAliasById(AliasId(1)) }
            verify { botRepository.removeAliasById(AliasId(2)) }
        }

        @Test
        fun `negative - no force - there are users in the group`() {
            // given
            every { botRepository.getMembersByGroupId(groupId) } returns listOf(
                Member(memberName = memberName1, userId = UserId(1)),
                Member(memberName = memberName2, userId = UserId(2)),
            )

            // when
            assertThat {
                botService.removeGroup(chatId = chatId, groupName = groupName, force = false)
            }.isFailure().isInstanceOf(BotReplyException.IntegrityViolationError::class)
        }

        @Test
        fun `positive - force`() {
            // given
            every { botRepository.getMembersByGroupId(groupId) } returns listOf(
                Member(memberName = memberName1, userId = UserId(1)),
                Member(memberName = memberName2, userId = UserId(2)),
            )

            // when
            botService.removeGroup(chatId = chatId, groupName = groupName, force = true)

            // then
            verify { botRepository.removeMemberFromGroupByName(groupId, memberName1) }
            verify { botRepository.removeMemberFromGroupByName(groupId, memberName2) }
            verify { botRepository.removeAliasById(aliasId1) }
            verify { botRepository.removeAliasById(aliasId2) }
            verify { botRepository.removeGroupById(groupId) }
        }
    }

    @Nested
    inner class `addMembers - tests` {

        @Test
        fun positive() {
            // given
            val groupName = GroupName("qwe")
            val groupId = GroupId(123)
            val newMember1 = Member(memberName = MemberName("xxx"))
            val newMember2 = Member(memberName = MemberName("yyy"))

            val existingMembers: List<Member> = listOf(
                Member(memberName = MemberName("qqq1")),
                Member(memberName = MemberName("qqq2")),
            )

            every {
                botRepository.getAliasByName(chatId = chatId, aliasName = groupName)
            } returns GroupAlias(
                aliasName = groupName,
                chatId = chatId,
                groupId = groupId,
                aliasId = AliasId(456)
            )

            every { botRepository.getMembersByGroupId(groupId) } returns existingMembers

            // when
            botService.addMembers(chatId = chatId, groupName = groupName, newMembers = setOf(newMember1, newMember2))

            // then
            verify { botRepository.addMember(groupId = groupId, member = newMember1) }
            verify { botRepository.addMember(groupId = groupId, member = newMember2) }
        }
    }

    @Nested
    inner class `removeMembersFromGroup - tests` {

        @Test
        fun positive() {
            // given
            val groupId = GroupId(456)
            val groupName = GroupName("qwe")

            val member1 = Member(memberName = MemberName("aaa"))
            val member2 = Member(memberName = MemberName("bbb"))

            val userId3 = UserId(111)
            val member3 = Member(memberName = MemberName("bbb"), userId = userId3)

            every {
                botRepository.getAliasByName(chatId = chatId, aliasName = groupName)
            } returns GroupAlias(
                chatId = ChatId(123),
                aliasName = groupName,
                groupId = groupId,
                aliasId = AliasId(1)
            )

            // when
            botService.removeMembersFromGroup(
                chatId = chatId,
                groupName = groupName,
                members = setOf(member1, member2, member3)
            )

            // then
            verify { botRepository.removeMemberFromGroupByName(groupId = groupId, memberName = member1.memberName) }
            verify { botRepository.removeMemberFromGroupByName(groupId = groupId, memberName = member2.memberName) }
            verify { botRepository.removeMemberFromGroupByUserId(groupId, userId3) }
        }
    }

    @Nested
    inner class `removeMembersFromChat - tests` {

        @Test
        fun positive() {
            // given
            val member1 = Member(memberName = MemberName("aaa"))
            val member2 = Member(memberName = MemberName("bbb"))

            val userId3 = UserId(111)
            val member3 = Member(memberName = MemberName("bbb"), userId = userId3)

            // when
            botService.removeMembersFromChat(
                chatId = chatId,
                members = setOf(member1, member2, member3)
            )

            // then
            verify { botRepository.removeMemberFromChatByName(chatId, member1.memberName) }
            verify { botRepository.removeMemberFromChatByName(chatId, member2.memberName) }
            verify { botRepository.removeMemberFromChatByUserId(chatId, userId3) }
        }
    }

    @Nested
    inner class `removeAlias - tests` {

        private val groupId = GroupId(123)

        private val groupAlias1 = GroupAlias(
            chatId = chatId,
            aliasName = GroupName("alias-1"),
            groupId = groupId,
            aliasId = AliasId(111)
        )
        private val groupAlias2 = GroupAlias(
            chatId = chatId,
            aliasName = GroupName("alias-2"),
            groupId = groupId,
            aliasId = AliasId(222)
        )

        @Test
        fun positive() {
            // given
            every { botRepository.getAliasByName(chatId, groupAlias1.aliasName) } returns groupAlias1
            every { botRepository.getAliasesByGroupId(groupId) } returns listOf(groupAlias1, groupAlias2)

            // when
            botService.removeAlias(chatId, aliasName = groupAlias1.aliasName)

            // then
            verifyOrder {
                botRepository.getChatByIdForUpdate(chatId)
                botRepository.removeAliasById(groupAlias1.aliasId)
            }
        }

        @Test
        fun `negative - cannot delete last name`() {
            every { botRepository.getAliasByName(chatId, groupAlias1.aliasName) } returns groupAlias1
            every { botRepository.getAliasesByGroupId(groupId) } returns listOf(groupAlias1)

            assertThat { botService.removeAlias(chatId, aliasName = groupAlias1.aliasName) }
                .isFailure()
                .isInstanceOf(BotReplyException.ValidationError::class)
        }
    }
}
