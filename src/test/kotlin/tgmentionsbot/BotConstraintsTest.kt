package tgmentionsbot

import assertk.all
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import org.junit.jupiter.api.Test

internal class BotConstraintsTest {

    @Test
    fun `parse command and group`() {
        val matchResult = BotConstraints.REGEX_CMD_GROUP.matchEntire("/cmd apple123")
        assertThat(matchResult)
            .isNotNull()
            .all {
                transform { it.groups["group"]?.value }.isEqualTo("apple123")
            }
    }

    @Test
    fun `parse command and group with text`() {
        val matchResult = BotConstraints.REGEX_CMD_GROUP_WITH_TAIL.matchEntire("/cmd apple123 some text, 123!")
        assertThat(matchResult)
            .isNotNull()
            .all {
                transform { it.groups["group"]?.value }.isEqualTo("apple123")
            }
    }

    @Test
    fun `parse command and members`() {
        val matchResult = BotConstraints.REGEX_CMD_MEMBERS.matchEntire("/cmd user1 user2 user3")
        assertThat(matchResult).isNotNull()
    }

    @Test
    fun `parse command and group with members`() {
        val matchResult = BotConstraints.REGEX_CMD_GROUP_MEMBERS.matchEntire("/cmd apple123 user1 user2 user3")
        assertThat(matchResult)
            .isNotNull()
            .all {
                transform { it.groups["group"]?.value }.isEqualTo("apple123")
            }
    }

    @Test
    fun `parse command and group with alias`() {
        val matchResult = BotConstraints.REGEX_CMD_GROUP_ALIAS.matchEntire("/cmd apple123 fruit456")
        assertThat(matchResult)
            .isNotNull()
            .all {
                transform { it.groups["group"]?.value }.isEqualTo("apple123")
                transform { it.groups["alias"]?.value }.isEqualTo("fruit456")
            }
    }
}
