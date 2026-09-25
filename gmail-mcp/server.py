"""MCP server that sends email from several Gmail accounts (e.g. work and personal).

Runs over stdio. Register it with your MCP client, for example:

    claude mcp add gmail-multi -- /path/to/.venv/bin/python /path/to/gmail-mcp/server.py
"""

from __future__ import annotations

from googleapiclient.errors import HttpError
from mcp.server.mcpserver import MCPServer

from gmail_accounts import build_message, check_recipients, gmail_service, load_config, split_addresses

config = load_config()

mcp = MCPServer(
    name="gmail-multi",
    instructions=(
        "Sends email from one of several Gmail accounts. Call list_accounts to see them. "
        f"If the user does not name an account, use {config.default_account!r}. "
        "Health and personal reports go from the 'personal' account."
    ),
)


@mcp.tool()
def list_accounts() -> dict:
    """List the configured sender accounts, which one is the default, and any recipient allowlists."""
    return {
        "default_account": config.default_account,
        "accounts": [
            {
                "name": a.name,
                "email": a.email,
                "signed_in": a.token_file.exists(),
                "allowed_recipients": a.allowed_recipients or "any",
            }
            for a in config.accounts.values()
        ],
    }


@mcp.tool()
def send_email(
    to: str,
    subject: str,
    body: str,
    account: str | None = None,
    html: str | None = None,
    cc: str | None = None,
    bcc: str | None = None,
) -> dict:
    """Send an email.

    Args:
        to: Recipient address, or several separated by commas.
        subject: Subject line.
        body: Plain-text body (always sent; shown by clients that don't render HTML).
        account: Sender account name from list_accounts, e.g. "personal" or "work". Defaults to the default account.
        html: Optional HTML version of the body.
        cc: Optional comma-separated Cc addresses.
        bcc: Optional comma-separated Bcc addresses.
    """
    name = account or config.default_account
    if name not in config.accounts:
        raise ValueError(f"Unknown account {name!r}. Choose one of {sorted(config.accounts)}.")
    acct = config.accounts[name]

    to_list, cc_list, bcc_list = split_addresses(to), split_addresses(cc), split_addresses(bcc)
    if not to_list:
        raise ValueError("At least one recipient is required.")
    check_recipients(acct, to_list + cc_list + bcc_list)

    message = build_message(acct, to_list, subject, body, html=html, cc=cc_list, bcc=bcc_list)
    try:
        sent = gmail_service(acct).users().messages().send(userId="me", body=message).execute()
    except HttpError as e:
        raise RuntimeError(f"Gmail API error sending from {acct.email}: {e}") from e
    return {"sent_from": acct.email, "to": to_list, "message_id": sent["id"], "thread_id": sent.get("threadId")}


if __name__ == "__main__":
    mcp.run()
