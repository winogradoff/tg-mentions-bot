from dataclasses import dataclass
from enum import Enum, auto
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


class AuthorizationError(RuntimeError):
    pass


class IllegalStateError(RuntimeError):
    pass
