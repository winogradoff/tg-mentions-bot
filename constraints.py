import re

MIN_DATABASE_POOL_CONNECTIONS = 2
MAX_DATABASE_POOL_CONNECTIONS = 5

MAX_GROUPS_PER_CHAT = 10
MAX_GROUP_NAME_LENGTH = 10
MAX_ALIASES_PER_GROUP = 3
MAX_MEMBERS_PER_GROUP = 20

_REGEX_CMD = r"(?:[@a-zA-Z0-9]|[-_])+"
_REGEX_GROUP = r"(?:[a-zA-Z0-9]|[а-яА-ЯёЁ]|[-_])+"
_REGEX_MEMBER = r"(?:[@\w]|[-])+"

MESSAGE_FOR_GROUP = "a-z, а-я, цифры, дефис и подчёркивание"
MESSAGE_FOR_MEMBER = "буквы, цифры, дефис и подчёркивание"

REGEX_CMD_GROUP = re.compile(fr"^/({_REGEX_CMD})\s+(?P<group>{_REGEX_GROUP})$")
REGEX_CMD_GROUP_RENAME = re.compile(fr"^/({_REGEX_CMD})\s+(?P<group>{_REGEX_GROUP})\s+(?P<new_group>{_REGEX_GROUP})$")

REGEX_CMD_GROUP_MESSAGE = re.compile(fr'^/({_REGEX_CMD})\s+(?P<group>{_REGEX_GROUP})(\s+(.|\n)*)*')
REGEX_CMD_GROUP_ALIAS = re.compile(fr"^/({_REGEX_CMD})\s+(?P<group>{_REGEX_GROUP})\s+(?P<alias>{_REGEX_GROUP})$")
REGEX_CMD_GROUP_MEMBERS = re.compile(fr'^/({_REGEX_CMD})\s+(?P<group>{_REGEX_GROUP})(\s+(?P<member>{_REGEX_MEMBER}))+$')
