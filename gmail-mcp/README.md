# gmail-multi: an MCP server for sending from two Gmail accounts

A small MCP server that sends email through the Gmail API from more than one Google account, such as **work** and **personal**. You pick the sender on each call. The default is `personal`, so the weekly Wrist Log health report goes to your personal inbox unless you say otherwise.

It has two tools:

| Tool | What it does |
|---|---|
| `list_accounts` | Shows the configured senders, the default one, whether each is signed in, and any recipient allowlist. |
| `send_email` | `to`, `subject`, `body`, plus optional `account`, `html`, `cc` and `bcc`. |

It asks for only the `gmail.send` permission. The server can send mail, but it can't read, search or delete anything in either mailbox.

---

## Step 1: Create a Google Cloud project and turn on the Gmail API

1. Go to <https://console.cloud.google.com/> and sign in with your **personal** account.
2. Create a project, for example `wrist-log-mailer`.
3. Go to **APIs & Services → Library**, search for **Gmail API** and click **Enable**.

## Step 2: Set up the OAuth consent screen

1. Go to **APIs & Services → OAuth consent screen** (also called **Google Auth Platform → Branding / Audience**).
2. For User type, choose **External**. This lets both a @gmail.com account and a work account sign in.
3. Fill in an app name and your email address. You can skip the logo and domains.
4. Under **Data access / Scopes**, add `https://www.googleapis.com/auth/gmail.send`.
5. Under **Audience**, add both your personal and work addresses as **test users**.

> **About the 7-day limit.** When an External app is in **Testing** status, its refresh tokens expire after 7 days. Your Sunday report would then start failing a week after you sign in. Once everything works, go to **Audience** and click **Publish app** to move it to **In production**. You don't need Google's verification for your own use. You'll see an "unverified app" warning when you sign in. Click **Advanced → Go to … (unsafe)** to continue. It's your app, so this is safe.

## Step 3: Create a Desktop OAuth client

1. Go to **APIs & Services → Credentials → Create credentials → OAuth client ID**.
2. For Application type, choose **Desktop app**.
3. Download the JSON file, rename it to `client_secret.json` and put it in this folder. Git ignores it, so it won't be committed.

## Step 4: Install

```sh
cd gmail-mcp
python3 -m venv .venv
.venv/bin/pip install -r requirements.txt
```

This needs Python 3.10 or later. On Windows, use `.venv\Scripts\pip` and `.venv\Scripts\python` instead.

## Step 5: Describe your two accounts

```sh
cp accounts.example.json accounts.json
```

Edit `accounts.json`:

```json
{
  "default_account": "personal",
  "accounts": {
    "personal": {
      "email": "you@gmail.com",
      "token_file": "tokens/personal.json",
      "allowed_recipients": ["you@gmail.com"]
    },
    "work": {
      "email": "you@yourcompany.com",
      "token_file": "tokens/work.json",
      "allowed_recipients": []
    }
  }
}
```

- `allowed_recipients` is a safety net. An account with a non-empty list can only send to those addresses. With `["you@gmail.com"]` on personal, the health report can only ever reach you, even if a prompt goes wrong. An empty list means the account can send to anyone.
- **Work account on Google Workspace:** some admins block third-party OAuth apps. If sign-in in Step 6 says the app is blocked, ask your admin to trust your client ID. Or create a second OAuth client in a Cloud project owned by your work domain, and point that account at it by adding `"client_secret_file": "client_secret_work.json"`.

## Step 6: Sign in to each account once

```sh
.venv/bin/python auth.py personal
.venv/bin/python auth.py work
```

A browser tab opens for each command. Sign in as the matching account and approve **Send email on your behalf**. The refresh tokens are saved to `tokens/`, which git ignores and which only your user can read. To revoke access later, go to <https://myaccount.google.com/permissions>.

## Step 7: Connect it to Claude

**Claude Code:**

```sh
claude mcp add gmail-multi -- /full/path/to/gmail-mcp/.venv/bin/python /full/path/to/gmail-mcp/server.py
```

**Claude Desktop:** add this to `claude_desktop_config.json` (Settings → Developer → Edit Config), then restart the app:

```json
{
  "mcpServers": {
    "gmail-multi": {
      "command": "/full/path/to/gmail-mcp/.venv/bin/python",
      "args": ["/full/path/to/gmail-mcp/server.py"]
    }
  }
}
```

## Step 8: Test it

Ask Claude:

> Use gmail-multi to list my accounts, then send a test email from my personal account to me with the subject "Wrist Log test".

Check your personal inbox. Then try the same from `work`.

## Step 9: Route the weekly health report

Update the Sunday 8:54 a.m. scheduled task's prompt so it ends with something like this:

> …then send the report with gmail-multi `send_email`, `account: "personal"`, `to: "you@gmail.com"`, using the HTML version of the report as `html` and a plain-text summary as `body`.

**Where the task runs matters.** This is a local stdio server, so it's only reachable from Claude running on the same computer (Claude Desktop or Claude Code there). A scheduled task that runs in the cloud can't reach it. For a cloud task, the simplest option is to connect the built-in Gmail connector to your personal account, since it sends from whichever account it's connected to. Use this server when you want to choose between accounts from your own machine.

---

## Files

| File | Purpose |
|---|---|
| `server.py` | The MCP server (stdio) with the `list_accounts` and `send_email` tools. |
| `auth.py` | One-time browser sign-in for each account. |
| `gmail_accounts.py` | Loads the config, refreshes tokens, builds MIME messages, enforces the allowlist. |
| `accounts.example.json` | Template for `accounts.json`. |

You can set the environment variables `GMAIL_MCP_CONFIG` and `GMAIL_MCP_CLIENT_SECRET` to keep the config and client secret somewhere other than this folder.

## Troubleshooting

- **`No token for account …`**: run `python auth.py <account>`.
- **`invalid_grant` / token revoked**: the app was probably still in Testing (see the note in Step 2), or you revoked access. Publish the app, then run `auth.py` again.
- **`Access blocked: … has not completed the Google verification process`**: that account isn't listed as a test user, or the app isn't published yet.
- **`403 … Gmail API has not been used in project`**: enable the Gmail API (Step 1) in the same project as your OAuth client.
