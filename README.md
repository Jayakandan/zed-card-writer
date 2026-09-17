# ZED Card Writer

Android app that writes the cardholder **name** and **balance** directly into a
Mifare Classic card's own memory, in the exact byte format the Teensy/VP3300
terminal reads. Replaces typing into the Serial Monitor.

---

## Getting the APK from GitHub

GitHub's build runners already have the Android SDK, so pushing this project
builds the APK for you. Run these from inside the `ZedCardWriter` folder.

**With the GitHub CLI (`gh`) — fastest:**

```bash
cd ZedCardWriter

git init
git add .
git commit -m "ZED Card Writer: Mifare Classic name/balance writer"
git branch -M main

# creates the repo and pushes; this push starts the build
gh repo create zed-card-writer --private --source=. --push

# watch the build (takes about 2-3 minutes)
gh run watch

# download the finished APK into the current folder
gh run download --name ZedCardWriter-release-apk
```

That leaves `app-release.apk` in your folder. Copy it to the phone and install
it (you will need to allow "install from unknown sources").

**Without the CLI**, make an empty repo on github.com first, then:

```bash
cd ZedCardWriter
git init
git add .
git commit -m "ZED Card Writer: Mifare Classic name/balance writer"
git branch -M main
git remote add origin https://github.com/<your-user>/zed-card-writer.git
git push -u origin main
```

Then open the repo's **Actions** tab, click the running "Build APK" job, and
download `ZedCardWriter-release-apk` from the **Artifacts** section at the
bottom of the run page.

**Rebuilding after a change:** commit and push, and the APK rebuilds. Or press
"Run workflow" on the Actions tab without changing anything.

**Building locally instead:** open the folder in Android Studio and choose
Build → Build Bundle(s)/APK → Build APK.

---

## Read this before you build: phone compatibility

Mifare Classic is **not** supported by every Android phone. It needs an NXP NFC
controller. Most Samsung handsets have one. Many phones with Broadcom or
Qualcomm NFC controllers — including several recent Pixels — cannot read or
write Mifare Classic at all, and no app can change that.

The app detects this on the first tap and tells you explicitly, listing the
technologies the tag reports. If `android.nfc.tech.MifareClassic` is missing
from that list, the phone is the problem, not the card or the app — try a
Samsung.

---

## Using it

1. Pick the action for the next tap:
   - **Read card only** — shows what is on the card, changes nothing.
   - **Write name and set balance** — sets both to what you typed.
   - **Top up** — adds the amount to whatever balance is already there.
2. Type the name and/or amount (`500`, `12.50`, `$25.00` all work).
3. Hold the card flat against the back of the phone until the log settles.

Every write is proved by reading the block back off the card and comparing.
A write that reports success but does not read back identically is reported as
a failure, not a success.

---

## The on-card format (the contract with the Teensy)

Mifare Classic 1K: 16 sectors x 4 blocks. Absolute block = `sector * 4 + blockInSector`.
Block 3 of every sector is the sector trailer (keys and access bits) — writing
data there permanently bricks that sector, so the app refuses to.

**Sector 1 is used:**

| Absolute block | Contents |
|---|---|
| 4 | balance |
| 5 | name |
| 6 | free |
| 7 | trailer — never written |

**Balance block (16 bytes)**

```
[0..3]   ASCII "BAL1"   marker, so a factory-blank block of zeros is never
                        mistaken for a balance of zero
[4..7]   uint32 big-endian, balance in CENTS
[8..15]  zero
```

Money is stored as integer cents, never a float — $1.50 is `150`. Repeated
debits over a card's life would accumulate rounding error otherwise.

**Name block (16 bytes)** — ASCII, NUL-padded, truncated to 16 characters.

This format was cross-checked against the Teensy sketch's own
`readBalanceFromCard()` decoder, byte for byte, across the full uint32 range
including `$0.00`, `$1.50`, `$500.00` and the 4-byte maximum, plus name
truncation and blank-block rejection. All passed.

To change the sector or blocks, edit `CardFormat.kt` **and** the matching
constants in the Arduino sketch, or the terminal will stop reading these cards.

---

## Adding name display to the Teensy sketch

The current sketch reads the balance but not the name. To show the cardholder
name the app writes, add this near `readBalanceFromCard()`:

```cpp
#define NAME_BLOCK_IN_SEC 1
#define NAME_MAX_LEN      16

inline uint8_t getActiveNameBlock() {
  return (activeSector * 4) + NAME_BLOCK_IN_SEC;   // sector 1 -> block 5
}

// Reads the cardholder name the Android app wrote. Returns false if blank.
bool readNameFromCard(char* out /* [NAME_MAX_LEN+1] */) {
  uint8_t data[16];
  if (!readCardBlockRaw(getActiveNameBlock(), data)) return false;
  if (data[0] == 0x00) return false;               // blank block
  uint8_t i = 0;
  for (; i < NAME_MAX_LEN && data[i] != 0x00; i++) out[i] = (char)data[i];
  out[i] = '\0';
  return (i > 0);
}
```

Then in `loop()`, after the sector authenticates and before the balance work:

```cpp
char holder[NAME_MAX_LEN + 1];
String cardholderName = readNameFromCard(holder) ? String(holder) : String("CARD");
Serial.println("Cardholder: " + cardholderName);
```

and pass `cardholderName` to `showDebitOnDisplay(...)` in place of the
hard-coded `"CARD"`.

Block 5 is in the same sector as block 4, so it needs no extra authentication —
the existing Crypto-1 session covers it.

---

## One note on the sketch's float display

The card always stores exact integer cents, so stored money is never lossy.
But the sketch's `centsToDollars()` returns a `float`, and a 32-bit float stops
representing cents exactly above about **$65,536**. Balances beyond that will
display and convert slightly wrong. Irrelevant at $500; worth knowing if these
cards are ever loaded with large amounts. Printing from the integer cents
directly (`cents / 100` and `cents % 100`) avoids it entirely.

---

## Keys

The app tries the same published default Mifare keys as the Arduino sketch, key
A then key B. A factory-blank card answers to the first (`FFFFFFFFFFFF`). If
your cards are keyed to something custom, add it to `CANDIDATE_KEYS` in
`CardFormat.kt` and to the sketch's list.

## Signing

The release build is signed with the debug key so CI produces an installable
APK with no secrets. That is fine for in-house validator hardware. Swap in a
real keystore before distributing publicly.
