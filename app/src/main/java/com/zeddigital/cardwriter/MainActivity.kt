package com.zeddigital.cardwriter

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.MifareClassic
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * ZED DIGITAL - Mifare Classic card writer.
 *
 * Writes the cardholder name and balance directly into the card's own memory
 * (sector 1: block 4 = balance, block 5 = name), in the exact byte format the
 * Teensy/VP3300 terminal reads. See CardFormat.kt for that contract.
 *
 * Three actions, chosen before tapping:
 *   READ    - show what is on the card, change nothing
 *   WRITE   - set the name and set the balance to the amount entered
 *   TOP UP  - add the amount entered to whatever balance is already there
 *
 * Every write is proved by reading the block back off the card and comparing.
 * A write command that merely returns "ok" is not evidence that anything
 * persisted.
 */
class MainActivity : Activity() {

    private var nfcAdapter: NfcAdapter? = null
    private var pendingIntent: PendingIntent? = null

    private lateinit var statusView: TextView
    private lateinit var uidView: TextView
    private lateinit var cardStateView: TextView
    private lateinit var nameInput: EditText
    private lateinit var amountInput: EditText
    private lateinit var modeGroup: RadioGroup
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusView = findViewById(R.id.statusView)
        uidView = findViewById(R.id.uidView)
        cardStateView = findViewById(R.id.cardStateView)
        nameInput = findViewById(R.id.nameInput)
        amountInput = findViewById(R.id.amountInput)
        modeGroup = findViewById(R.id.modeGroup)
        logView = findViewById(R.id.logView)
        logScroll = findViewById(R.id.logScroll)

        findViewById<Button>(R.id.clearLogButton).setOnClickListener {
            logView.text = ""
        }

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        if (nfcAdapter == null) {
            setStatus("This phone has no NFC hardware.", true)
            log("FATAL: no NFC adapter on this device.")
        } else {
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                PendingIntent.FLAG_MUTABLE else 0
            pendingIntent = PendingIntent.getActivity(
                this, 0,
                Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                flags
            )
        }

        log("Ready. Sector ${CardFormat.SECTOR}: block ${CardFormat.BALANCE_BLOCK} = balance, " +
            "block ${CardFormat.NAME_BLOCK} = name.")
    }

    override fun onResume() {
        super.onResume()
        val adapter = nfcAdapter
        if (adapter == null) return
        if (!adapter.isEnabled) {
            setStatus("NFC is switched off - enable it in Settings.", true)
        } else {
            setStatus("Choose an action, then tap the card.", false)
        }
        adapter.enableForegroundDispatch(this, pendingIntent, null, null)
    }

    override fun onPause() {
        super.onPause()
        nfcAdapter?.disableForegroundDispatch(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val tag: Tag? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
        else
            @Suppress("DEPRECATION") intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)

        if (tag == null) return
        handleTag(tag)
    }

    // ── the actual card work ───────────────────────────────────────────────

    private fun handleTag(tag: Tag) {
        val uid = CardFormat.bytesToHex(tag.id)
        uidView.text = "UID: $uid"
        log("\n---- tap: $uid ----")

        val mifare = MifareClassic.get(tag)
        if (mifare == null) {
            // This is the one failure that is NOT fixable in software.
            setStatus("This phone cannot do Mifare Classic.", true)
            cardStateView.text = ""
            log("FAILED: MifareClassic.get() returned null.")
            log("Either this card is not Mifare Classic, or - more likely -")
            log("this phone's NFC chipset does not support Mifare Classic at all.")
            log("Mifare Classic needs an NXP NFC controller. Many phones with")
            log("Broadcom/Qualcomm controllers (including several recent Pixels)")
            log("cannot read or write these cards no matter what app you use.")
            log("Tag reports these technologies:")
            for (t in tag.techList) log("   $t")
            log("If MifareClassic is absent from that list, try a Samsung handset.")
            return
        }

        try {
            mifare.connect()
            mifare.timeout = 3000

            val sector = CardFormat.SECTOR
            if (sector >= mifare.sectorCount) {
                setStatus("Card too small - no sector $sector.", true)
                log("FAILED: card has only ${mifare.sectorCount} sectors.")
                return
            }

            val key = authenticate(mifare, sector)
            if (key == null) {
                setStatus("Could not unlock sector $sector.", true)
                log("FAILED: none of the ${CardFormat.CANDIDATE_KEYS.size} candidate keys")
                log("authenticated sector $sector with key A or key B.")
                log("This card uses a custom key that is not in the list.")
                return
            }
            log("Authenticated sector $sector with ${CardFormat.bytesToHex(key)}")

            when (modeGroup.checkedRadioButtonId) {
                R.id.modeRead -> doRead(mifare)
                R.id.modeWrite -> doWrite(mifare)
                R.id.modeTopUp -> doTopUp(mifare)
                else -> doRead(mifare)
            }

        } catch (e: Exception) {
            setStatus("Card error - hold it still and retry.", true)
            log("EXCEPTION: ${e.javaClass.simpleName}: ${e.message}")
            log("Usually this means the card left the field mid-operation.")
        } finally {
            try { mifare.close() } catch (_: Exception) { }
        }
    }

    /** Tries every candidate key, key A then key B. Returns the key that worked. */
    private fun authenticate(mifare: MifareClassic, sector: Int): ByteArray? {
        for (key in CardFormat.CANDIDATE_KEYS) {
            try {
                if (mifare.authenticateSectorWithKeyA(sector, key)) return key
            } catch (_: Exception) { }
            try {
                if (mifare.authenticateSectorWithKeyB(sector, key)) return key
            } catch (_: Exception) { }
        }
        return null
    }

    private fun doRead(mifare: MifareClassic) {
        val balBlock = mifare.readBlock(CardFormat.BALANCE_BLOCK)
        val nameBlock = mifare.readBlock(CardFormat.NAME_BLOCK)

        log("block ${CardFormat.BALANCE_BLOCK}: ${CardFormat.bytesToHex(balBlock)}")
        log("block ${CardFormat.NAME_BLOCK}: ${CardFormat.bytesToHex(nameBlock)}")

        val cents = CardFormat.decodeBalance(balBlock)
        val name = CardFormat.decodeName(nameBlock)

        showCardState(name, cents)
        setStatus("Card read.", false)
    }

    private fun doWrite(mifare: MifareClassic) {
        val name = nameInput.text.toString().trim()
        val cents = CardFormat.dollarsToCents(amountInput.text.toString())

        if (name.isEmpty() && cents == null) {
            setStatus("Enter a name and/or an amount first.", true)
            log("Nothing to write - both fields are empty or invalid.")
            return
        }
        if (amountInput.text.toString().isNotBlank() && cents == null) {
            setStatus("Amount is not a valid number.", true)
            log("Amount \"${amountInput.text}\" rejected - use a form like 500 or 12.50")
            return
        }

        if (name.isNotEmpty()) {
            if (!writeAndVerify(mifare, CardFormat.NAME_BLOCK, CardFormat.encodeName(name), "name")) return
            if (name.length > CardFormat.NAME_MAX_LEN) {
                log("NOTE: name truncated to ${CardFormat.NAME_MAX_LEN} characters.")
            }
        }
        if (cents != null) {
            if (!writeAndVerify(mifare, CardFormat.BALANCE_BLOCK, CardFormat.encodeBalance(cents), "balance")) return
        }

        doRead(mifare)
        setStatus("Written and verified on the card.", false)
    }

    private fun doTopUp(mifare: MifareClassic) {
        val add = CardFormat.dollarsToCents(amountInput.text.toString())
        if (add == null) {
            setStatus("Enter a valid top-up amount.", true)
            log("Top-up amount \"${amountInput.text}\" rejected.")
            return
        }

        val existing = CardFormat.decodeBalance(mifare.readBlock(CardFormat.BALANCE_BLOCK))
        if (existing == null) {
            log("No balance on this card yet - treating the top-up as the opening balance.")
        }
        val current = existing ?: 0L
        val updated = current + add

        log("balance ${CardFormat.centsToDollars(current)} + ${CardFormat.centsToDollars(add)} " +
            "= ${CardFormat.centsToDollars(updated)}")

        if (!writeAndVerify(mifare, CardFormat.BALANCE_BLOCK, CardFormat.encodeBalance(updated), "balance")) return

        // A name typed alongside a top-up is written too, so one tap can do both.
        val name = nameInput.text.toString().trim()
        if (name.isNotEmpty()) {
            writeAndVerify(mifare, CardFormat.NAME_BLOCK, CardFormat.encodeName(name), "name")
        }

        doRead(mifare)
        setStatus("Topped up and verified on the card.", false)
    }

    /**
     * Writes a block, then reads it back off the card and compares. Only a
     * matching read-back counts as success.
     */
    private fun writeAndVerify(mifare: MifareClassic, block: Int, data: ByteArray, what: String): Boolean {
        // Guard: block 3 of any sector is the trailer (keys + access bits).
        // Writing data there permanently bricks the sector.
        if (block % 4 == 3) {
            log("REFUSED: block $block is a sector trailer - writing it would brick the sector.")
            setStatus("Refused unsafe write to sector trailer.", true)
            return false
        }

        mifare.writeBlock(block, data)
        val back = mifare.readBlock(block)
        if (!back.contentEquals(data)) {
            setStatus("Write did not stick - card may be read-only.", true)
            log("VERIFY FAILED on block $block ($what)")
            log("  wrote: ${CardFormat.bytesToHex(data)}")
            log("  read : ${CardFormat.bytesToHex(back)}")
            return false
        }
        log("VERIFIED $what on block $block: ${CardFormat.bytesToHex(data)}")
        return true
    }

    // ── ui helpers ─────────────────────────────────────────────────────────

    private fun showCardState(name: String?, cents: Long?) {
        val n = name ?: "(no name on card)"
        val b = if (cents != null) "$" + CardFormat.centsToDollars(cents) else "(no balance on card)"
        cardStateView.text = "$n\n$b"
        cardStateView.visibility = View.VISIBLE
    }

    private fun setStatus(text: String, isError: Boolean) {
        statusView.text = text
        statusView.setTextColor(if (isError) 0xFFD32F2F.toInt() else 0xFF1B5E20.toInt())
        if (isError) Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    private fun log(line: String) {
        logView.append(line + "\n")
        logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
    }
}
