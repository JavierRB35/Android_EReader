package com.javierrb35.bibliotecaia.sync.coordinator

import android.content.Context
import com.javierrb35.bibliotecaia.sync.coordinator.credentials.AndroidCredentialVault
import com.javierrb35.bibliotecaia.sync.coordinator.credentials.CoordinatorEnrollment
import com.javierrb35.bibliotecaia.sync.coordinator.onboarding.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Connect the actual Android view to the durable enrollment port.
 * Host scope MUST be lifecycle-bound; this is not registered as an Activity
 * and has no configured production gateway or default approval source.
 */
internal class CoordinatorOnboardingHost(
    context: Context,
    private val scope: CoroutineScope,
    transport: CoordinatorTransport,
    approval: BootstrapApproval,
    private val inventory: suspend () -> Unit
) {
    private val flow = UniqueLibraryOnboarding(EnrollmentOnboardingAdapter(
        CoordinatorEnrollment(AndroidCredentialVault(context.applicationContext)), transport, approval))
    private var pending: Job? = null
    val view: UniqueLibraryOnboardingView = UniqueLibraryOnboardingView(context, object : UniqueLibraryOnboardingView.Actions {
        override fun create() = submit { flow.create() }
        override fun link(libraryId: String, pairingCode: String) = submit { flow.link(libraryId, pairingCode) }
        override fun retry() = submit { flow.retry() }
        override fun check() = refresh()
        override fun continueToInventory() {
            if (pending?.isActive == true) return
            pending = scope.launch(Dispatchers.Main.immediate) {
                try {
                    if (withContext(Dispatchers.IO) { flow.state() } is OnboardingState.Linked)
                        inventory()
                } finally {
                    view.render(OnboardingResult(withContext(Dispatchers.IO) { flow.state() },
                        OnboardingFeedback.NONE))
                }
            }
        }
    })

    fun refresh(): Unit = submit { OnboardingResult(flow.state(), OnboardingFeedback.NONE) }

    private fun submit(operation: () -> OnboardingResult): Unit {
        if (pending?.isActive == true) return
        pending = scope.launch(Dispatchers.Main.immediate) {
            view.render(withContext(Dispatchers.IO) { operation() })
        }
    }
}
