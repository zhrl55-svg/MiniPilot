"""Execution-side gate: who may run what on this computer (contract C4.1, C3.0).

The decision key is the *remote device id*, not a local session: a remote call
belongs to no local conversation, so reusing session-scoped permissions would
grant the wrong thing to the wrong caller.
"""

from __future__ import annotations

import re
import time
from dataclasses import dataclass
from typing import Any

BYPASS = "bypass"
ASK = "ask"
DENY = "deny"

#: Actions whose risk a checker can actually judge from their text.
RISK = {
    "info": "low",
    "files": "low",
    "file.get": "medium",
    "file.put": "high",
    "shell": "high",
    "screen": "medium",
    "notify": "low",
    "approve": "low",
    "stop": "low",
}

#: First tokens of a command that only reads. Anything not here is a write.
#: Deliberately narrow: `awk`, `sed`, `find`, `git`, `python`, `node` all have a
#: documented path to writing or executing, so they are not "read-only".
SAFE_COMMANDS = {
    "ls", "ll", "dir", "cat", "head", "tail", "less", "more", "wc", "file",
    "pwd", "whoami", "id", "hostname", "uname", "date", "df", "du", "free",
    "ps", "tasklist", "env", "printenv", "which", "whereis", "type",
    "echo", "stat", "grep", "rg", "sort", "uniq", "cut", "tree", "uptime",
    "nvidia-smi", "systeminfo", "ver",
}

#: Sub-commands that write even though the binary is in SAFE_COMMANDS.
WRITE_SUBCOMMANDS: dict[str, set[str]] = {}

#: A shell metacharacter that can turn a read into a write or reach outside.
UNSAFE_TOKENS = (">", "<", "`", "$(", "${", "&&", "||", ";", "&", "\n")

#: Commands that destroy data even when they "look simple". An approval grant
#: never covers these: they ask every time. Narrow on purpose — a false
#: positive is one extra card, a false negative is unapproved data loss.
_DESTRUCTIVE = re.compile(
    r"\b(rm|rmdir|rd|del|erase|format|mkfs|dd|shutdown|reboot|poweroff|"
    r"diskpart|takeown|bcdedit|reg\s+delete)\b",
    re.IGNORECASE,
)


def shell_is_destructive(command: str) -> bool:
    return bool(_DESTRUCTIVE.search(command or ""))


@dataclass
class Decision:
    mode: str
    risk: str
    reason: str

    @property
    def allowed(self) -> bool:
        return self.mode == BYPASS


def shell_is_read_only(command: str) -> tuple[bool, str]:
    """Best-effort: is this command purely observational?

    Conservative on purpose — a false `True` here means an unapproved write,
    a false `False` only means an extra approval card.
    """
    text = (command or "").strip()
    if not text:
        return False, "empty command"
    for token in UNSAFE_TOKENS:
        if token in text:
            return False, f"contains {token!r}"
    for segment in text.split("|"):
        words = segment.strip().split()
        if not words:
            return False, "empty pipeline segment"
        head = words[0]
        if head not in SAFE_COMMANDS:
            return False, f"{head!r} is not a read-only command"
        blocked = WRITE_SUBCOMMANDS.get(head)
        if blocked and len(words) > 1:
            for word in words[1:]:
                if word in blocked:
                    return False, f"{head} {word} writes"
    return True, "read-only"


class Gate:
    def __init__(self, cfg):
        self.cfg = cfg
        policy = cfg.policy or {}
        self.default_mode = str(policy.get("default", ASK)).lower()
        self.by_sender: dict[str, str] = {
            str(k): str(v).lower() for k, v in (policy.get("by_sender") or {}).items()
        }
        self.per_action: dict[str, Any] = {
            str(k): v for k, v in (policy.get("actions") or {}).items()
        }
        self.read_only_bypass = bool(policy.get("allow_read_only_shell", True))
        #: How long one approval covers the same action from the same device.
        #: Without it every step of a multi-step task raised its own card,
        #: which trains people to tap "allow" without reading. 0 disables.
        try:
            self.grant_s = float(policy.get("approval_grant_s", 600))
        except (TypeError, ValueError):
            self.grant_s = 600.0
        self._grants: dict[tuple[str, str], float] = {}

    def note_approval(self, sender_id: str, action: str) -> None:
        """A person allowed [action] for [sender]: trust it for grant_s."""
        if self.grant_s > 0:
            self._grants[(sender_id, action)] = time.monotonic() + self.grant_s

    def _grant_left(self, sender_id: str, action: str) -> float:
        left = self._grants.get((sender_id, action), 0.0) - time.monotonic()
        return max(0.0, left)

    def _action_mode(self, action: str) -> tuple[str, str]:
        entry = self.per_action.get(action)
        if isinstance(entry, str):
            return entry.lower(), f"policy.actions[{action}]"
        if isinstance(entry, dict) and "mode" in entry:
            return str(entry["mode"]).lower(), f"policy.actions[{action}].mode"
        return self.default_mode, "policy.default"

    def decide(self, sender_id: str, action: str, args: dict[str, Any]) -> Decision:
        if action not in self.cfg.actions:
            return Decision(DENY, "n/a", f"{action!r} is not in this device's actions")

        risk = RISK.get(action, "high")

        # An explicitly trusted device skips everything below, including the
        # destructive check — that is what "trusted" means.
        if sender_id in self.by_sender:
            return Decision(self.by_sender[sender_id], risk, f"trusted device {sender_id}")

        if action == "shell":
            command = str(args.get("command", ""))
            # No grant covers destruction: it is exactly the class of call
            # that must be looked at, every time.
            if shell_is_destructive(command):
                return Decision(ASK, "high", "destructive shell command")
            if self.read_only_bypass:
                read_only, why = shell_is_read_only(command)
                if read_only:
                    return Decision(BYPASS, "low", f"shell is read-only ({why})")

        left = self._grant_left(sender_id, action)
        if left > 0:
            return Decision(BYPASS, risk, f"{action!r} was allowed {int(self.grant_s - left)}s ago")

        mode, source = self._action_mode(action)
        return Decision(mode, risk, f"{source} for {action!r}")


def path_is_inside(root: str, candidate: str) -> bool:
    import os

    real_root = os.path.realpath(root)
    real = os.path.realpath(candidate)
    return real == real_root or real.startswith(real_root + os.sep)


_SAFE_APP = re.compile(r"^[A-Za-z0-9 ._-]{1,64}$")


def app_label(value: str) -> str:
    return value if _SAFE_APP.match(value or "") else "hub"
