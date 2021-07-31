package tgmentionsbot

class BotConfig {

    val botUsername: String = getEnv("BOT_USERNAME")
    val botToken: String = getEnv("BOT_TOKEN")

    private fun getEnv(key: String): String =
            requireNotNull(System.getenv(key)) { "Required config parameter is null: [$key]" }
}
