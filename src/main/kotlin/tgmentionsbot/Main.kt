package tgmentionsbot

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import org.slf4j.LoggerFactory
import org.telegram.telegrambots.meta.TelegramBotsApi
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession


fun main() {
    LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)
            .let { it as Logger }
            .run { level = Level.DEBUG }

    val logger = LoggerFactory.getLogger("main")
    val botsApi = TelegramBotsApi(DefaultBotSession::class.java)
    val bot = Bot()

    logger.info("Registering bot [${bot.botUsername}]...")
    try {
        botsApi.registerBot(bot)
        logger.info("A bot [${bot.botUsername}] was registered successfully!")
    } catch (e: Exception) {
        logger.error("An error occurred when registering a bot [${bot.botUsername}]!", e)
    }
}
