package ac.mdiq.podvinci.core.util

import ac.mdiq.podvinci.model.feed.FeedItem
import ac.mdiq.podvinci.model.feed.FeedMedia
import ac.mdiq.podvinci.storage.preferences.UserPreferences
import org.apache.commons.lang3.StringUtils

object FeedItemUtil {
    @JvmStatic
    fun indexOfItemWithId(items: List<FeedItem?>, id: Long): Int {
        for (i in items.indices) {
            val item = items[i]
            if (item?.id == id) {
                return i
            }
        }
        return -1
    }

    @JvmStatic
    fun indexOfItemWithDownloadUrl(items: List<FeedItem?>, downloadUrl: String): Int {
        for (i in items.indices) {
            val item = items[i]
            if (item?.media?.download_url == downloadUrl) {
                return i
            }
        }
        return -1
    }

    @JvmStatic
    fun getIds(items: List<FeedItem>?): LongArray {
        if (items.isNullOrEmpty()) {
            return LongArray(0)
        }
        val result = LongArray(items.size)
        for (i in items.indices) {
            result[i] = items[i].id
        }
        return result
    }

    @JvmStatic
    fun getIdList(items: List<FeedItem>): List<Long> {
        val result: MutableList<Long> = ArrayList()
        for (item in items) {
            result.add(item.id)
        }
        return result
    }

    /**
     * Get the link for the feed item for the purpose of Share. It fallbacks to
     * use the feed's link if the named feed item has no link.
     */
    @JvmStatic
    fun getLinkWithFallback(item: FeedItem?): String? {
        if (item == null) {
            return null
        } else if (StringUtils.isNotBlank(item.link)) {
            return item.link
        } else if (item.feed != null && !item.feed!!.link.isNullOrEmpty()) {
            return item.feed!!.link
        }
        return null
    }

    @JvmStatic
    fun hasAlmostEnded(media: FeedMedia): Boolean {
        val smartMarkAsPlayedSecs = UserPreferences.smartMarkAsPlayedSecs
        return media.getDuration() > 0 && media.getPosition() >= media.getDuration() - smartMarkAsPlayedSecs * 1000
    }
}
