package tgmentionsbot

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.ConstructorBinding
import javax.validation.constraints.NotEmpty

@ConstructorBinding
@ConfigurationProperties("tgmentionsbot")
class BotProperties(
    @NotEmpty
    val botUsername: String,

    @NotEmpty
    val botToken: String
)
