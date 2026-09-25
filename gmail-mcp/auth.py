"""One-time sign-in for an account in accounts.json.

    python auth.py personal
    python auth.py work

Opens a browser, asks you to sign in to that Google account, and saves a
refresh token to the account's token_file.
"""

from __future__ import annotations

import sys

from google_auth_oauthlib.flow import InstalledAppFlow

from gmail_accounts import CLIENT_SECRET_PATH, SCOPES, load_config, save_credentials


def main() -> None:
    config = load_config()
    if len(sys.argv) != 2 or sys.argv[1] not in config.accounts:
        sys.exit(f"Usage: python auth.py <{'|'.join(config.accounts)}>")
    account = config.accounts[sys.argv[1]]

    client_secret = account.client_secret_file or CLIENT_SECRET_PATH
    if not client_secret.exists():
        sys.exit(f"{client_secret} not found. Download the OAuth client JSON from Google Cloud Console.")

    flow = InstalledAppFlow.from_client_secrets_file(str(client_secret), SCOPES)
    print(f"Sign in as {account.email} in the browser window that opens.")
    # login_hint pre-selects the right Google account; prompt=consent guarantees a refresh token.
    creds = flow.run_local_server(port=0, login_hint=account.email, prompt="consent")
    save_credentials(account, creds)
    print(f"Saved token for {account.name!r} to {account.token_file}")


if __name__ == "__main__":
    main()
