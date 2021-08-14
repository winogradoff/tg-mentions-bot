package tgmentionsbot

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import javax.validation.constraints.NotEmpty


@Component
@ConfigurationProperties("tgmentionsbot")
class BotProperties {

    @NotEmpty
    lateinit var botUsername: String

    @NotEmpty
    lateinit var botToken: String
}
