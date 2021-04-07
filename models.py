import base64
import logging
from dataclasses import dataclass
from enum import Enum, auto, IntEnum, unique
from typing import Optional

import msgpack


class Grant(Enum):
    READ_ACCESS = auto()
    WRITE_ACCESS = auto()
    CHANGE_CHAT_SETTINGS = auto()


@dataclass
class Chat:
    chat_id: int
    chat_title: Optional[str]
    chat_username: Optional[str]
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
    type: CallbackType
    user_id: Optional[int] = None
    group_id: Optional[int] = None

    _CALLBACK_FIELD_TYPE = 1
    _CALLBACK_FIELD_USER = 2
    _CALLBACK_FIELD_GROUP = 3

    def serialize(self) -> str:
        data: dict = {
            self._CALLBACK_FIELD_TYPE: self.type.value,
            self._CALLBACK_FIELD_USER: self.user_id,
            self._CALLBACK_FIELD_GROUP: self.group_id
        }
        data = {key: value for key, value in data.items() if value}
        data_bytes: bytes = msgpack.packb(data, use_bin_type=True)
        b85_bytes: bytes = base64.b85encode(data_bytes)
        result_str: str = b85_bytes.decode("ascii")
        logging.debug(
            f"data=[{data}],"
            f" bytes: {len(data_bytes)},"
            f" b85: {len(b85_bytes)},"
            f" str: {len(result_str)}"
        )
        return result_str

    @classmethod
    def deserialize(cls, s: str) -> 'CallbackData':
        b85_bytes = s.encode("ascii")
        data_bytes = base64.b85decode(b85_bytes)
        data = msgpack.unpackb(data_bytes, raw=False, strict_map_key=False)
        user_id = data.get(cls._CALLBACK_FIELD_USER)
        group_id = data.get(cls._CALLBACK_FIELD_GROUP)
        return CallbackData(
            type=CallbackType(int(data[cls._CALLBACK_FIELD_TYPE])),
            user_id=int(user_id) if user_id else None,
            group_id=int(group_id) if group_id else None,
        )


class AuthorizationError(RuntimeError):
    pass


class IllegalStateError(RuntimeError):
    pass
