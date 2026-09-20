# ZED NFC Card

A single-screen Android app that reads and writes a balance + cardholder name
to a Mifare Classic NFC card, with a sign-in gate and a MongoDB record of
every top-up.

## What changed from the original app

- Added a **sign-in screen** in front of the terminal (`LoginActivity`).
- The on-screen **activity log is gone**. The same detail now goes to Logcat
  only (tag `CardWriter`), for developers plugged into the device.
- Renamed the three actions:
  - "Read card only (change nothing)" → **Show amount**
  - "Write name and set balance" → **Set balance** (name-writing removed - this
    mode only ever writes the balance block now)
  - "Top up: add amount to balance" → **Add amount to balance**
- Added small animations: a pulsing ring on the header's NFC icon while the
  screen is waiting for a tap, a fade-in on the login screen, a shake on a
  wrong password, and a check/cross that appears briefly after a write
  succeeds or fails.
- After a successful **Add amount to balance**, the app posts one record
  (cardholder name from the card, amount added, and the time) to a MongoDB
  collection via the Atlas Data API.

## Important: about the sign-in and the database key

Both the login password and the database key are compiled into the app as
constants (via `BuildConfig`, see below) rather than typed in each time.
That's convenient, but it means **anyone with the APK can decompile it and
recover them** - this is exactly how the original APK you sent was inspected
to understand what it did. Two consequences:

1. The sign-in screen stops casual/incidental use of the terminal. It is
   *not* a substitute for real authentication if this app will be handed to
   people you don't fully trust with the card data.
2. The MongoDB key must be a **restricted, insert-only Data API key**
   scoped to one collection - never your cluster's main connection string or
   an admin key. If it leaks out of a decompiled APK, the worst case should
   be "someone can insert junk rows," not "someone has your whole database."

## One-time setup

### 1. Get this project onto GitHub

If you're comfortable with git:

```bash
cd zed-nfc-card
git init
git add .
git commit -m "Initial import"
git branch -M main
git remote add origin https://github.com/<your-account>/<your-repo>.git
git push -u origin main
```

If you'd rather not use git commands: create a new empty repository on
GitHub, then on the repo page use **Add file → Upload files** and drag the
whole `zed-nfc-card` folder's contents in.

### 2. Add the repo secrets

GitHub repo → **Settings → Secrets and variables → Actions → New repository
secret**. Add each of these (all optional - the app builds fine without them,
using harmless placeholders, but the login and database sync won't be real
until you do):

| Secret name | What it is |
|---|---|
| `LOGIN_USERNAME` | The username for the sign-in screen |
| `LOGIN_PASSWORD_SHA256` | **SHA-256 hash** of the password - never the plaintext. See below for how to generate it. |
| `DATA_API_URL` | Your MongoDB Atlas Data API base URL (see step 3) |
| `DATA_API_KEY` | A restricted, insert-only Atlas Data API key |
| `DATA_SOURCE` | Your Atlas cluster name, e.g. `Cluster0` |
| `DATABASE_NAME` | The database to write to |
| `COLLECTION_NAME` | The collection to write to, e.g. `topups` |

To generate the password hash, run one of these with your chosen password
in place of `yourpassword`, and paste the output as `LOGIN_PASSWORD_SHA256`:

```bash
# macOS / Linux
echo -n "yourpassword" | shasum -a 256

# Windows PowerShell
$h = [System.Security.Cryptography.SHA256]::Create()
-join ($h.ComputeHash([Text.Encoding]::UTF8.GetBytes("yourpassword")) | ForEach-Object { $_.ToString("x2") })
```

### 3. Turn on the MongoDB Atlas Data API

This is done in your Atlas project - I don't have access to your account and
can't do this step for you.

1. In Atlas, open **App Services** (or **Data API** under your cluster,
   depending on your Atlas UI version) and enable the Data API for your
   cluster.
2. Copy the **URL Endpoint** it gives you - that's `DATA_API_URL`. (The app
   appends `/action/insertOne` itself, so the base URL is fine.)
3. Create a **new Data API key**. If your Atlas plan lets you scope App
   Services rules per-collection, restrict this key's role to **insert-only**
   on the one collection you'll use for top-ups. That's `DATA_API_KEY`.
4. Note your cluster name (`DATA_SOURCE`), the database you want
   (`DATABASE_NAME`), and pick/create a collection for the records
   (`COLLECTION_NAME`, e.g. `topups`).

Each inserted record looks like:

```json
{
  "name": "ADAM",
  "topUpAmount": 12.5,
  "topUpAmountCents": 1250,
  "topUpTime": "2026-09-20T19:04:11.000Z"
}
```

### 4. Get the built APK

Every push to `main` (and manual runs) builds the app automatically:

1. GitHub repo → **Actions** tab → open the latest **Build APK** run.
2. Scroll to **Artifacts** → download `zed-nfc-card-debug-apk`.
3. Unzip it, copy the `.apk` to the phone, and install it (you'll need
   "Install unknown apps" allowed for whatever app you use to open it).

You can also trigger a build manually from the Actions tab without pushing
anything, via **Run workflow**.

## A note on testing

I wrote and reviewed this code carefully against the original app's logic,
but I could not compile or run it myself in the environment I was working
in - it has no Android SDK or the network access a real build needs. GitHub
Actions will be the first real compile. If that build fails, copy the error
from the Actions log back and I'll fix it directly.

## Where things live

- `app/src/main/java/com/zeddigital/cardwriter/`
  - `LoginActivity.kt` - the sign-in screen
  - `MainActivity.kt` - the card terminal (read / set balance / top up)
  - `CardFormat.kt` - how balance/name are encoded onto the card (unchanged logic)
  - `TopUpApi.kt` - posts the top-up record to MongoDB
- `app/src/main/res/values/strings.xml` - all on-screen text
- `app/src/main/res/values/colors.xml` - the colour palette
- `app/src/main/res/layout/` - the two screens
- `.github/workflows/build-apk.yml` - the CI build
