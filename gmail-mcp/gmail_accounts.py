"""Shared helpers: load the account config, build Gmail clients, compose messages."""

from __future__ import annotations

import base64
import json
import os
from dataclasses import dataclass, field
from email.message import EmailMessage
from pathlib import Path

from google.auth.transport.requests import Request
from google.oauth2.credentials import Credentials
from googleapiclient.discovery import build

# gmail.send can send mail but cannot read, list or delete anything in the mailbox.
SCOPES = ["https://www.googleapis.com/auth/gmail.send"]

BASE_DIR = Path(__file__).resolve().parent
CONFIG_PATH = Path(os.environ.get("GMAIL_MCP_CONFIG", BASE_DIR / "accounts.json"))
CLIENT_SECRET_PATH = Path(os.environ.get("GMAIL_MCP_CLIENT_SECRET", BASE_DIR / "client_secret.json"))


@dataclass
class Account:
    name: str
    email: str
    token_file: Path
    # Optional per-account OAuth client, for a work domain that only trusts its own client.
    client_secret_file: Path | None = None
    # Empty list means any recipient is allowed.
    allowed_recipients: list[str] = field(default_factory=list)


@dataclass
class Config:
    default_account: str
    accounts: dict[str, Account]


def _resolve(config_path: Path, value: str) -> Path:
    p = Path(value)
    return p if p.is_absolute() else config_path.parent / p


def load_config(path: Path = CONFIG_PATH) -> Config:
    if not path.exists():
        raise FileNotFoundError(f"{path} not found. Copy accounts.example.json to accounts.json and edit it.")
    raw = json.loads(path.read_text())
    accounts = {}
    for name, spec in raw["accounts"].items():
        token_file = _resolve(path, spec["token_file"])
        accounts[name] = Account(
            name=name,
            email=spec["email"],
            token_file=token_file,
            client_secret_file=_resolve(path, spec["client_secret_file"]) if spec.get("client_secret_file") else None,
            allowed_recipients=[r.lower() for r in spec.get("allowed_recipients", [])],
        )
    default = raw.get("default_account") or next(iter(accounts))
    if default not in accounts:
        raise ValueError(f"default_account {default!r} is not one of {sorted(accounts)}")
    return Config(default_account=default, accounts=accounts)


def load_credentials(account: Account) -> Credentials:
    if not account.token_file.exists():
        raise RuntimeError(
            f"No token for account {account.name!r}. Run: python auth.py {account.name}"
        )
    creds = Credentials.from_authorized_user_file(str(account.token_file), SCOPES)
    if not creds.valid:
        if creds.expired and creds.refresh_token:
            creds.refresh(Request())
            save_credentials(account, creds)
        else:
            raise RuntimeError(
                f"Token for {account.name!r} is invalid or revoked. Run: python auth.py {account.name}"
            )
    return creds


def save_credentials(account: Account, creds: Credentials) -> None:
    account.token_file.parent.mkdir(parents=True, exist_ok=True)
    account.token_file.write_text(creds.to_json())
    account.token_file.chmod(0o600)


def gmail_service(account: Account):
    return build("gmail", "v1", credentials=load_credentials(account), cache_discovery=False)


def split_addresses(value: str | list[str] | None) -> list[str]:
    if not value:
        return []
    if isinstance(value, str):
        value = value.split(",")
    return [a.strip() for a in value if a.strip()]


def check_recipients(account: Account, recipients: list[str]) -> None:
    if not account.allowed_recipients:
        return
    blocked = [r for r in recipients if r.lower() not in account.allowed_recipients]
    if blocked:
        raise PermissionError(
            f"Account {account.name!r} may only send to {account.allowed_recipients}; blocked: {blocked}"
        )


def build_message(
    account: Account,
    to: list[str],
    subject: str,
    body: str,
    html: str | None = None,
    cc: list[str] | None = None,
    bcc: list[str] | None = None,
) -> dict:
    msg = EmailMessage()
    msg["From"] = account.email
    msg["To"] = ", ".join(to)
    if cc:
        msg["Cc"] = ", ".join(cc)
    if bcc:
        msg["Bcc"] = ", ".join(bcc)
    msg["Subject"] = subject
    msg.set_content(body)
    if html:
        msg.add_alternative(html, subtype="html")
    return {"raw": base64.urlsafe_b64encode(msg.as_bytes()).decode()}
