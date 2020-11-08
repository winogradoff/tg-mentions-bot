import json
from dataclasses import dataclass
from enum import Enum, auto, IntEnum, unique
from typing import Optional


class Grant(Enum):
    READ_ACCESS = auto()
    WRITE_ACCESS = auto()
    CHANGE_CHAT_SETTINGS = auto()


@dataclass
class Chat:
    chat_id: int
    is_anarchy_enabled: bool


@dataclass
class Group:
    group_id: int


@dataclass
class GroupAlias:
    chat_id: int
    group_id: int
    alias_name: str
    alias_id: Optional[int] = None


@dataclass
class Member:
    member_name: str
    member_id: Optional[int] = None
    user_id: Optional[int] = None


@unique
class CallbackType(IntEnum):
    CANCEL = 1
    SELECT_GROUP = 2


@dataclass
class CallbackData:
    callback_type: CallbackType
    chat_id: Optional[int] = None
    user_id: Optional[int] = None
    group_id: Optional[int] = None

    def to_json(self) -> str:
        return json.dumps(
            {
                "type": self.callback_type.value,
                "chat": self.chat_id,
                "user": self.user_id,
                "group": self.group_id
            }
        )

    @classmethod
    def from_json(cls, data: str) -> 'CallbackData':
        json_data = json.loads(data)
        return CallbackData(
            callback_type=CallbackType(json_data["type"]),
            chat_id=json_data.get("chat"),
            user_id=json_data.get("user"),
            group_id=json_data.get("group"),
        )


class AuthorizationError(RuntimeError):
    pass


class IllegalStateError(RuntimeError):
    pass
