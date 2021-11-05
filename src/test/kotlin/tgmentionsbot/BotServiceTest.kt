package tgmentionsbot

import assertk.assertThat
import assertk.assertions.containsExactlyInAnyOrder
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isNotNull
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
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
    inner class `getMembers - tests` {

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
            val member1 = Member(memberId = MemberId(1), memberName = MemberName("aaa"), userId = UserId(111))
            val member2 = Member(memberId = MemberId(2), memberName = MemberName("bbb"), userId = UserId(222))

            every { botRepository.getAliasByName(chatId = chatId, aliasName = groupName) } returns groupAlias
            every { botRepository.getMembersByGroupId(groupId = groupId) } returns listOf(member1, member2)

            // when
            val members = botService.getMembers(chatId = chatId, groupName = groupName)

            // then
            assertThat(members).containsExactlyInAnyOrder(member1, member2)
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
    inner class `removeMembers - tests` {

        @Test
        fun positive() {
            // given
            val groupId = GroupId(456)
            val groupName = GroupName("qwe")
            val member1 = Member(memberName = MemberName("aaa"))
            val member2 = Member(memberName = MemberName("bbb"))

            every {
                botRepository.getAliasByName(chatId = chatId, aliasName = groupName)
            } returns GroupAlias(
                chatId = ChatId(123),
                aliasName = groupName,
                groupId = groupId,
                aliasId = AliasId(1)
            )

            // when
            botService.removeMembers(
                chatId = chatId,
                groupName = groupName,
                members = setOf(member1, member2)
            )

            // then
            verify { botRepository.removeMemberByName(groupId = groupId, memberName = member1.memberName) }
            verify { botRepository.removeMemberByName(groupId = groupId, memberName = member2.memberName) }
        }
    }
}
