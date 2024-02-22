package ac.mdiq.podcini.core.sync

import ac.mdiq.podcini.core.R

enum class SynchronizationProviderViewData(@JvmField val identifier: String, val summaryResource: Int, val iconResource: Int) {
    GPODDER_NET(
        "GPODDER_NET",
        R.string.gpodnet_description,
        R.drawable.gpodder_icon
    ),
    NEXTCLOUD_GPODDER(
        "NEXTCLOUD_GPODDER",
        R.string.synchronization_summary_nextcloud,
        R.drawable.nextcloud_logo
    );

    companion object {
        @JvmStatic
        fun fromIdentifier(provider: String): SynchronizationProviderViewData? {
            for (synchronizationProvider in entries) {
                if (synchronizationProvider.identifier == provider) {
                    return synchronizationProvider
                }
            }
            return null
        }
    }
}