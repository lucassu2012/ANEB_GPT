package com.aneb.probe

import android.app.Application
import com.aneb.probe.privacy.LocalPrivacyDeletionOperationOwner

class AnebProbeApplication : Application() {
    internal val localPrivacyDeletionOperationOwner: LocalPrivacyDeletionOperationOwner by lazy(
        LazyThreadSafetyMode.SYNCHRONIZED,
    ) {
        LocalPrivacyDeletionOperationOwner.create(this)
    }
}
