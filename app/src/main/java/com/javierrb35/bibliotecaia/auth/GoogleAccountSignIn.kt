package com.javierrb35.bibliotecaia.auth

import android.app.Activity
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential

class GoogleAccountSignIn(private val activity: Activity) {
    private val manager = CredentialManager.create(activity)

    suspend fun signIn(repository: AccountRepository): AccountSession {
        if (!repository.configuration.googleConfigured) throw AccountFailure(AccountFailure.Reason.NOT_CONFIGURED)
        val nonce = AuthPolicy.nonce()
        val option = GetSignInWithGoogleOption.Builder(repository.configuration.googleWebClientId)
            .setNonce(AuthPolicy.nonceHash(nonce)).build()
        val credential = manager.getCredential(activity, GetCredentialRequest.Builder().addCredentialOption(option).build()).credential
        if (credential !is CustomCredential || credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            throw AccountFailure(AccountFailure.Reason.INVALID_RESPONSE)
        }
        val idToken = GoogleIdTokenCredential.createFrom(credential.data).idToken
        return repository.signIn(idToken, nonce)
    }

    suspend fun clearProviderState() = manager.clearCredentialState(ClearCredentialStateRequest())
}
