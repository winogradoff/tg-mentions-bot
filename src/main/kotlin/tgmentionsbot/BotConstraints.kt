package tgmentionsbot


object BotConstraints {

    const val MAX_GROUPS_PER_CHAT = 10
    const val MAX_GROUP_NAME_LENGTH = 10
    const val MAX_MEMBER_NAME_LENGTH = 100
    const val MAX_ALIASES_PER_GROUP = 3
    const val MAX_MEMBERS_PER_GROUP = 20

    private const val BEGIN = "^"
    private const val END = "$"

    private const val CMD = """(?:[@a-zA-Z0-9]|[-_])+"""
    private const val GROUP = """(?:[a-zA-Z0-9]|[а-яА-ЯёЁ]|[-_])+"""
    private const val MEMBER = """(?:[@\w]|[-])+"""

    val REGEX_CMD_GROUP = """$BEGIN/($CMD)\s+(?<group>$GROUP)$END""".toRegex()
    val REGEX_CMD_GROUP_WITH_TAIL = """^/($CMD)\s+(?<group>$GROUP)(\s+(.|\n)*)*""".toRegex()
    val REGEX_CMD_MEMBERS = """$BEGIN/($CMD)(\s+(?<member>$MEMBER))+$END""".toRegex()
    val REGEX_CMD_GROUP_MEMBERS = """$BEGIN/($CMD)\s+(?<group>$GROUP)(\s+(?<member>$MEMBER))+$END""".toRegex()
    val REGEX_CMD_GROUP_ALIAS = """$BEGIN/($CMD)\s+(?<group>$GROUP)\s+(?<alias>$GROUP)$END""".toRegex()

    const val MESSAGE_FOR_GROUP = "a-z, а-я, цифры, дефис и подчёркивание"
    const val MESSAGE_FOR_MEMBER = "буквы, цифры, дефис и подчёркивание"
}
