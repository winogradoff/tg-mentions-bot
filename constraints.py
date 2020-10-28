import re

MAX_GROUPS_PER_CHAT = 10
MAX_GROUP_NAME_LENGTH = 10
MAX_ALIASES_PER_GROUP = 3

REGEX_CMD = r"(?:[@a-zA-Z0-9]|[-_])+"
REGEX_GROUP = r"(?:[a-zA-Z0-9]|[а-яА-ЯёЁ]|[-_])+"
REGEX_MEMBER = r"(?:[@\w]|[-])+"

REGEX_CMD_GROUP = re.compile(fr"^/({REGEX_CMD})\s+(?P<group>{REGEX_GROUP})$")
REGEX_CMD_GROUP_RENAME = re.compile(fr"^/({REGEX_CMD})\s+(?P<group>{REGEX_GROUP})\s+(?P<new_group>{REGEX_GROUP})$")

REGEX_CMD_GROUP_MESSAGE = re.compile(fr'^/({REGEX_CMD})\s+(?P<group>{REGEX_GROUP})(\s+(.|\n)*)*')
REGEX_CMD_GROUP_ALIAS = re.compile(fr"^/({REGEX_CMD})\s+(?P<group>{REGEX_GROUP})\s+(?P<alias>{REGEX_GROUP})$")
REGEX_CMD_GROUP_MEMBERS = re.compile(fr'^/({REGEX_CMD})\s+(?P<group>{REGEX_GROUP})(\s+(?P<member>{REGEX_MEMBER}))+$')
