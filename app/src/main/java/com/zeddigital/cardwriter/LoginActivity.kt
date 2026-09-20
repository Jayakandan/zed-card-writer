package com.zeddigital.cardwriter

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import java.security.MessageDigest

/**
 * A lightweight sign-in gate in front of the card terminal.
 *
 * Worth being upfront about: this only stops casual/incidental use of the app.
 * The username and a *hash* of the password are compiled into the app (see
 * BuildConfig / app/build.gradle), and anyone with the APK can decompile it
 * and recover that hash the same way this app itself was inspected to build
 * this feature. If these credentials need to resist a determined attacker
 * rather than just gate casual access, the check has to happen against a
 * server on every login, not against a constant baked into the client.
 */
class LoginActivity : Activity() {

    private lateinit var usernameInput: EditText
    private lateinit var passwordInput: EditText
    private lateinit var loginError: TextView
    private lateinit var loginButton: Button
    private lateinit var loginCard: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        val logo = findViewById<ImageView>(R.id.loginLogo)
        val title = findViewById<TextView>(R.id.loginTitle)
        val subtitle = findViewById<TextView>(R.id.loginSubtitle)
        loginCard = findViewById(R.id.loginCard)
        usernameInput = findViewById(R.id.usernameInput)
        passwordInput = findViewById(R.id.passwordInput)
        loginError = findViewById(R.id.loginError)
        loginButton = findViewById(R.id.loginButton)

        loginButton.setOnClickListener { attemptLogin() }

        // Simple staggered fade-in so the screen doesn't just snap into place.
        for ((index, view) in listOf(logo, title, subtitle, loginCard).withIndex()) {
            view.translationY = 24f
            view.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(index * 80L)
                .setDuration(320)
                .setInterpolator(AccelerateDecelerateInterpolator())
                .start()
        }
    }

    private fun attemptLogin() {
        val user = usernameInput.text.toString().trim()
        val pass = passwordInput.text.toString()

        val expectedUser = BuildConfig.LOGIN_USERNAME
        val expectedHash = BuildConfig.LOGIN_PASSWORD_SHA256
        val actualHash = sha256Hex(pass)

        val ok = user.equals(expectedUser, ignoreCase = false) &&
                actualHash.equals(expectedHash, ignoreCase = true)

        if (ok) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        } else {
            loginError.visibility = View.VISIBLE
            shake(loginCard)
        }
    }

    private fun shake(view: View) {
        val anim = ObjectAnimator.ofFloat(
            view, "translationX",
            0f, -18f, 18f, -14f, 14f, -8f, 8f, 0f
        )
        anim.duration = 420
        AnimatorSet().apply {
            play(anim)
            start()
        }
    }

    private fun sha256Hex(text: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
