package com.zeddigital.cardwriter

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.MifareClassic
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import java.util.Arrays

/**
 * Single-screen NFC terminal. Three actions, chosen with the push buttons:
 *   - Show amount   : read-only, changes nothing on the card
 *   - Set balance   : writes the amount field to the card's balance block
 *   - Add amount to balance : reads the existing balance, adds the amount, writes it back,
 *                             then reports the top-up (name on card, amount, time) to MongoDB
 *
 * Cardholder-name writing has been removed entirely - this screen only ever
 * reads the name that's already on the card, for display and for the top-up
 * record. The activity log from the previous version is gone from the UI;
 * the same detail now goes to Logcat only, under tag "CardWriter".
 */
class MainActivity : Activity() {

    private val TAG = "CardWriter"

    private lateinit var statusView: TextView
    private lateinit var cardNameView: TextView
    private lateinit var cardBalanceView: TextView
    private lateinit var cardUidView: TextView
    private lateinit var amountInput: EditText
    private lateinit var resultIcon: ImageView
    private lateinit var pulseRing: View

    private lateinit var modeReadButton: Button
    private lateinit var modeWriteButton: Button
    private lateinit var modeTopUpButton: Button

    private lateinit var scanOverlayScrim: View
    private lateinit var scanPulse: View
    private lateinit var scanTitle: TextView
    private lateinit var scanSubtitle: TextView

    /** Which action fires on the next tap. Defaults to the safe, read-only one. */
    private var selectedMode: Int = R.id.modeRead

    private var nfcAdapter: NfcAdapter? = null
    private var pendingIntent: PendingIntent? = null
    private var pulseAnimator: AnimatorSet? = null
    private var scanPulseAnimator: AnimatorSet? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusView = findViewById(R.id.statusView)
        cardNameView = findViewById(R.id.cardNameView)
        cardBalanceView = findViewById(R.id.cardBalanceView)
        cardUidView = findViewById(R.id.cardUidView)
        amountInput = findViewById(R.id.amountInput)
        resultIcon = findViewById(R.id.resultIcon)
        pulseRing = findViewById(R.id.pulseRing)

        modeReadButton = findViewById(R.id.modeRead)
        modeWriteButton = findViewById(R.id.modeWrite)
        modeTopUpButton = findViewById(R.id.modeTopUp)

        scanOverlayScrim = findViewById(R.id.scanOverlayScrim)
        scanPulse = findViewById(R.id.scanPulse)
        scanTitle = findViewById(R.id.scanTitle)
        scanSubtitle = findViewById(R.id.scanSubtitle)
        findViewById<View>(R.id.scanCancelButton).setOnClickListener { hideScanOverlay() }

        modeReadButton.setOnClickListener { selectMode(R.id.modeRead) }
        modeWriteButton.setOnClickListener { selectMode(R.id.modeWrite) }
        modeTopUpButton.setOnClickListener { selectMode(R.id.modeTopUp) }
        selectMode(R.id.modeRead)

        findViewById<View>(R.id.logoutButton).setOnClickListener {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
        }

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        if (nfcAdapter == null) {
            setStatus("This phone has no NFC hardware.", true)
        } else {
            val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            pendingIntent = PendingIntent.getActivity(
                this, 0, Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), flags
            )
        }
        Log.d(TAG, "Ready. Sector 1: block 4 = balance, block 5 = name.")
    }

    override fun onResume() {
        super.onResume()
        val adapter = nfcAdapter ?: return
        if (!adapter.isEnabled) {
            setStatus("NFC is switched off - enable it in Settings.", true)
        } else {
            setStatus("Choose an action, then tap the card.", false)
        }
        adapter.enableForegroundDispatch(this, pendingIntent, null, null)
        startPulse()
        showScanOverlay(getString(R.string.scan_ready_title), getString(R.string.scan_ready_subtitle))
    }

    override fun onPause() {
        super.onPause()
        nfcAdapter?.disableForegroundDispatch(this)
        stopPulse()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val tag: Tag? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra("android.nfc.extra.TAG", Tag::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra("android.nfc.extra.TAG")
        }
        tag?.let { handleTag(it) }
    }

    // ---------------------------------------------------------------- mode buttons

    private fun selectMode(modeId: Int) {
        selectedMode = modeId
        style(modeReadButton, modeId == R.id.modeRead)
        style(modeWriteButton, modeId == R.id.modeWrite)
        style(modeTopUpButton, modeId == R.id.modeTopUp)
    }

    private fun style(button: Button, selected: Boolean) {
        button.setBackgroundResource(if (selected) R.drawable.bg_mode_selected else R.drawable.bg_mode_unselected)
        button.setTextColor(resources.getColor(if (selected) R.color.text_on_brand else R.color.text_primary))
    }

    // ---------------------------------------------------------------- tap handling

    private fun handleTag(tag: Tag) {
        val uidHex = CardFormat.bytesToHex(tag.id)
        cardUidView.text = uidHex
        Log.d(TAG, "tap: $uidHex")

        showScanOverlay(busyTitleFor(selectedMode), getString(R.string.scan_ready_subtitle))

        val mifare = MifareClassic.get(tag)
        if (mifare == null) {
            hideScanOverlay()
            setStatus("This phone cannot do Mifare Classic.", true)
            cardNameView.text = getString(R.string.dash)
            cardBalanceView.text = getString(R.string.dash)
            Log.w(TAG, "MifareClassic.get() returned null. Tech list: ${tag.techList.joinToString()}")
            return
        }

        try {
            try {
                mifare.connect()
                mifare.timeout = 3000

                if (CardFormat.SECTOR >= mifare.sectorCount) {
                    setStatus("Card too small - no sector 1.", true)
                    Log.w(TAG, "card has only ${mifare.sectorCount} sectors")
                    return
                }

                val key = authenticate(mifare, CardFormat.SECTOR)
                if (key == null) {
                    setStatus("Could not unlock sector 1.", true)
                    Log.w(TAG, "none of the ${CardFormat.CANDIDATE_KEYS.size} candidate keys authenticated sector 1")
                    return
                }

                when (selectedMode) {
                    R.id.modeRead -> doRead(mifare)
                    R.id.modeWrite -> doSetBalance(mifare)
                    R.id.modeTopUp -> doTopUp(mifare)
                    else -> doRead(mifare)
                }
            } finally {
                try { mifare.close() } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            setStatus("Card error - hold it still and retry.", true)
            Log.e(TAG, "exception talking to card", e)
        } finally {
            hideScanOverlay()
        }
    }

    private fun busyTitleFor(modeId: Int): String = when (modeId) {
        R.id.modeWrite -> getString(R.string.scan_busy_write)
        R.id.modeTopUp -> getString(R.string.scan_busy_topup)
        else -> getString(R.string.scan_busy_read)
    }

    private fun authenticate(mifare: MifareClassic, sector: Int): ByteArray? {
        for (key in CardFormat.CANDIDATE_KEYS) {
            try {
                if (mifare.authenticateSectorWithKeyA(sector, key)) return key
            } catch (_: Exception) {}
            if (mifare.authenticateSectorWithKeyB(sector, key)) return key
        }
        return null
    }

    // ---------------------------------------------------------------- the three modes

    private fun doRead(mifare: MifareClassic) {
        val balBlock = mifare.readBlock(CardFormat.BALANCE_BLOCK)
        val nameBlock = mifare.readBlock(CardFormat.NAME_BLOCK)
        val cents = CardFormat.decodeBalance(balBlock)
        val name = CardFormat.decodeName(nameBlock)
        showCardState(name, cents)
        setStatus("Card read.", false)
    }

    /** "Set balance" - writes only the balance block. No name is ever written. */
    private fun doSetBalance(mifare: MifareClassic) {
        val cents = CardFormat.dollarsToCents(amountInput.text.toString())
        if (cents == null) {
            setStatus("Enter a valid amount.", true)
            return
        }
        if (writeAndVerify(mifare, CardFormat.BALANCE_BLOCK, CardFormat.encodeBalance(cents), "balance")) {
            doRead(mifare)
            setStatus("Balance set and verified on the card.", false)
            showResult(success = true)
        } else {
            showResult(success = false)
        }
    }

    /** "Add amount to balance" - reads the existing balance, adds to it, writes it back,
     *  then reports the top-up to the database. */
    private fun doTopUp(mifare: MifareClassic) {
        val add = CardFormat.dollarsToCents(amountInput.text.toString())
        if (add == null) {
            setStatus("Enter a valid top-up amount.", true)
            return
        }

        val existing = CardFormat.decodeBalance(mifare.readBlock(CardFormat.BALANCE_BLOCK))
        val current = existing ?: 0L
        val updated = current + add

        if (!writeAndVerify(mifare, CardFormat.BALANCE_BLOCK, CardFormat.encodeBalance(updated), "balance")) {
            showResult(success = false)
            return
        }

        val name = CardFormat.decodeName(mifare.readBlock(CardFormat.NAME_BLOCK))
        doRead(mifare)
        setStatus("Topped up and verified on the card.", false)
        showResult(success = true)

        val whenMillis = System.currentTimeMillis()
        TopUpApi.postTopUpAsync(name, add, whenMillis) { result ->
            result.fold(
                onSuccess = {
                    setStatus(getString(R.string.topup_sync_ok), false)
                },
                onFailure = { err ->
                    if (err is TopUpApi.NotConfigured) {
                        setStatus(getString(R.string.topup_sync_not_configured), false)
                    } else {
                        setStatus(getString(R.string.topup_sync_failed, err.message ?: "unknown error"), true)
                    }
                    Log.w(TAG, "top-up sync failed", err)
                }
            )
        }
    }

    private fun writeAndVerify(mifare: MifareClassic, block: Int, data: ByteArray, what: String): Boolean {
        if (block % 4 == 3) {
            Log.w(TAG, "refused to write block $block - it is a sector trailer")
            setStatus("Refused unsafe write to sector trailer.", true)
            return false
        }
        mifare.writeBlock(block, data)
        val back = mifare.readBlock(block)
        if (!Arrays.equals(back, data)) {
            setStatus("Write did not stick - card may be read-only.", true)
            Log.w(TAG, "verify failed on block $block ($what)")
            return false
        }
        Log.d(TAG, "verified $what on block $block: ${CardFormat.bytesToHex(data)}")
        return true
    }

    // ---------------------------------------------------------------- UI helpers

    private fun showCardState(name: String?, cents: Long?) {
        cardNameView.text = name ?: "No name on card"
        cardBalanceView.text = if (cents != null) "$" + CardFormat.centsToDollars(cents) else getString(R.string.dash)
    }

    private fun setStatus(text: String, isError: Boolean) {
        statusView.text = text
        if (isError) {
            statusView.setBackgroundResource(R.drawable.bg_status_error)
            statusView.setTextColor(resources.getColor(R.color.error))
            Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
        } else {
            statusView.setBackgroundResource(R.drawable.bg_status_ok)
            statusView.setTextColor(resources.getColor(R.color.ok))
        }
    }

    /** Brief centre-screen check / cross, for the moment an operation finishes. */
    private fun showResult(success: Boolean) {
        resultIcon.setImageResource(if (success) R.drawable.ic_check_circle else R.drawable.ic_error_circle)
        resultIcon.visibility = View.VISIBLE
        resultIcon.alpha = 0f
        resultIcon.scaleX = 0.6f
        resultIcon.scaleY = 0.6f

        resultIcon.animate()
            .alpha(1f).scaleX(1f).scaleY(1f)
            .setDuration(220)
            .withEndAction {
                mainHandler.postDelayed({
                    resultIcon.animate()
                        .alpha(0f)
                        .setDuration(220)
                        .withEndAction { resultIcon.visibility = View.GONE }
                        .start()
                }, 650)
            }
            .start()
    }

    /** The white "Ready to Scan" / "Writing…" card shown over a dim scrim. */
    private fun showScanOverlay(title: String, subtitle: String) {
        scanTitle.text = title
        scanSubtitle.text = subtitle
        if (scanOverlayScrim.visibility != View.VISIBLE) {
            scanOverlayScrim.visibility = View.VISIBLE
            scanOverlayScrim.alpha = 0f
            scanOverlayScrim.animate().alpha(1f).setDuration(180).start()
        }
        startScanPulse()
    }

    private fun hideScanOverlay() {
        stopScanPulse()
        scanOverlayScrim.animate()
            .alpha(0f)
            .setDuration(180)
            .withEndAction { scanOverlayScrim.visibility = View.GONE }
            .start()
    }

    /** Slow pulsing ring behind the header's NFC icon - purely decorative,
     *  signals "this screen is listening for a tap". */
    private fun startPulse() {
        pulseAnimator = pulse(pulseRing)
    }

    private fun stopPulse() {
        pulseAnimator?.cancel()
        pulseAnimator = null
        pulseRing.alpha = 0f
    }

    private fun startScanPulse() {
        if (scanPulseAnimator == null) {
            scanPulseAnimator = pulse(scanPulse)
        }
    }

    private fun stopScanPulse() {
        scanPulseAnimator?.cancel()
        scanPulseAnimator = null
        scanPulse.alpha = 0f
    }

    private fun pulse(target: View): AnimatorSet {
        val scaleX = ObjectAnimator.ofFloat(target, "scaleX", 1f, 1.8f).apply {
            repeatCount = ValueAnimator.INFINITE
        }
        val scaleY = ObjectAnimator.ofFloat(target, "scaleY", 1f, 1.8f).apply {
            repeatCount = ValueAnimator.INFINITE
        }
        val alpha = ObjectAnimator.ofFloat(target, "alpha", 0.55f, 0f).apply {
            repeatCount = ValueAnimator.INFINITE
        }
        return AnimatorSet().apply {
            playTogether(scaleX, scaleY, alpha)
            duration = 1400
            start()
        }
    }
}
