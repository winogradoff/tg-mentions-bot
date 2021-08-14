package tgmentionsbot

sealed class BotReplyException : RuntimeException() {

    abstract override val message: String

    data class AuthorizationError(override val message: String) : BotReplyException()
    data class ValidationError(override val message: String, val userMessage: String) : BotReplyException()
    data class NotFoundError(override val message: String, val userMessage: String) : BotReplyException()
    data class IntegrityViolationError(override val message: String, val userMessage: String) : BotReplyException()
}
