package app.unscroll.sync

import android.content.Context

object SyncBindings {
    fun provider(context: Context): SyncProvider = SelfHostedSyncProvider(context)

    fun provision(baseUrl: String): ProvisionResult = SyncAccount.create(baseUrl)
}
