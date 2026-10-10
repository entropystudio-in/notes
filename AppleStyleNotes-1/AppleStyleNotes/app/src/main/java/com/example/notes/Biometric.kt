package com.example.notes

import android.content.Context
import android.content.ContextWrapper
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

fun Context.findActivity(): FragmentActivity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is FragmentActivity) return c
        c = c.baseContext
    }
    return null
}

/** Asks for fingerprint / face / screen-lock PIN, pattern or password. */
fun authenticate(activity: FragmentActivity, title: String, onSuccess: () -> Unit, onFail: (String) -> Unit) {
    val allowed = BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL
    if (BiometricManager.from(activity).canAuthenticate(allowed) != BiometricManager.BIOMETRIC_SUCCESS) {
        onFail("Set up a screen lock (PIN, pattern, password or fingerprint) in Android settings first.")
        return
    }
    val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity),
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onSuccess()
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                if (errorCode != BiometricPrompt.ERROR_USER_CANCELED && errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON)
                    onFail(errString.toString())
            }
        })
    prompt.authenticate(
        BiometricPrompt.PromptInfo.Builder().setTitle(title).setAllowedAuthenticators(allowed).build()
    )
}
