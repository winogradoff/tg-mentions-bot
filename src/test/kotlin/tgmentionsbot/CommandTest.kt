package tgmentionsbot

import assertk.assertThat
import assertk.assertions.hasSize
import org.junit.jupiter.api.Test

internal class CommandTest {

    @Test
    fun `no command duplicates by key`() {
        val groupedByKey: Map<String, List<Command>> =
            Command.values()
                .asSequence()
                .flatMap { cmd -> cmd.keys.map { key -> key to cmd } }
                .groupBy(keySelector = { it.first }, valueTransform = { it.second })

        for ((key, commands) in groupedByKey.entries) {
            assertThat(commands, "key = [$key], commands=[$commands]").hasSize(1)
        }
    }
}
