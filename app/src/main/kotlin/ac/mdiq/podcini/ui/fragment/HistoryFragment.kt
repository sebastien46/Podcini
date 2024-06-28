package ac.mdiq.podcini.ui.fragment

import ac.mdiq.podcini.R
import ac.mdiq.podcini.storage.database.RealmDB.realm
import ac.mdiq.podcini.storage.database.RealmDB.runOnIOScope
import ac.mdiq.podcini.storage.model.Episode
import ac.mdiq.podcini.storage.model.EpisodeMedia
import ac.mdiq.podcini.storage.utils.EpisodeFilter
import ac.mdiq.podcini.storage.utils.SortOrder
import ac.mdiq.podcini.ui.actions.menuhandler.MenuItemUtils
import ac.mdiq.podcini.ui.activity.MainActivity
import ac.mdiq.podcini.ui.adapter.EpisodesAdapter
import ac.mdiq.podcini.ui.dialog.ConfirmationDialog
import ac.mdiq.podcini.ui.dialog.DatesFilterDialog
import ac.mdiq.podcini.ui.dialog.EpisodeSortDialog
import ac.mdiq.podcini.ui.view.viewholder.EpisodeViewHolder
import ac.mdiq.podcini.util.DateFormatter
import ac.mdiq.podcini.util.Logd
import ac.mdiq.podcini.util.event.EventFlow
import ac.mdiq.podcini.util.event.FlowEvent
import ac.mdiq.podcini.util.sorting.EpisodesPermutors.getPermutor
import android.content.DialogInterface
import android.os.Bundle
import android.view.*
import androidx.annotation.OptIn
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.*
import kotlin.math.min

@UnstableApi class HistoryFragment : BaseEpisodesFragment() {

    private var sortOrder : SortOrder = SortOrder.PLAYED_DATE_NEW_OLD
    private var startDate : Long = 0L
    private var endDate : Long = Date().time

    var allHistory: List<Episode> = listOf()

    override fun getPrefName(): String {
        return TAG
    }

    @UnstableApi override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val root = super.onCreateView(inflater, container, savedInstanceState)

        Logd(TAG, "fragment onCreateView")
        toolbar.inflateMenu(R.menu.playback_history)
        toolbar.setTitle(R.string.playback_history_label)
        updateToolbar()
        emptyView.setIcon(R.drawable.ic_history)
        emptyView.setTitle(R.string.no_history_head_label)
        emptyView.setMessage(R.string.no_history_label)

        return root
    }

    override fun createListAdaptor() {
        listAdapter = object : EpisodesAdapter(activity as MainActivity) {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EpisodeViewHolder {
                return object: EpisodeViewHolder(mainActivityRef.get()!!, parent) {
                    override fun setPubDate(item: Episode) {
                        val playDate = Date(item.media?.getLastPlayedTime()?:0L)
                        pubDate.text = DateFormatter.formatAbbrev(activity, playDate)
                        pubDate.setContentDescription(DateFormatter.formatForAccessibility(playDate))
                    }
                }
            }
            override fun onCreateContextMenu(menu: ContextMenu, v: View, menuInfo: ContextMenu.ContextMenuInfo?) {
                super.onCreateContextMenu(menu, v, menuInfo)
                MenuItemUtils.setOnClickListeners(menu) { item: MenuItem -> this@HistoryFragment.onContextItemSelected(item) }
            }
        }
        listAdapter.setOnSelectModeListener(this)
        recyclerView.adapter = listAdapter
    }

    override fun onStart() {
        super.onStart()
        procFlowEvents()
    }

    override fun onStop() {
        super.onStop()
        cancelFlowEvents()
    }

    @OptIn(UnstableApi::class) override fun onMenuItemClick(item: MenuItem): Boolean {
        if (super.onOptionsItemSelected(item)) return true
        when (item.itemId) {
            R.id.episodes_sort -> HistorySortDialog().show(childFragmentManager.beginTransaction(), "SortDialog")
            R.id.filter_items -> {
                val dialog = object: DatesFilterDialog(requireContext(), 0L) {
                    override fun initParams() {
                        val calendar = Calendar.getInstance()
                        calendar.add(Calendar.YEAR, -1) // subtract 1 year
                        timeFilterFrom = calendar.timeInMillis
                        showMarkPlayed = false
                    }
                    override fun callback(timeFilterFrom: Long, timeFilterTo: Long, includeMarkedAsPlayed: Boolean) {
                        EventFlow.postEvent(FlowEvent.HistoryEvent(sortOrder, timeFilterFrom, timeFilterTo))
                    }
                }
                dialog.show()
            }
            R.id.clear_history_item -> {
                val conDialog: ConfirmationDialog = object : ConfirmationDialog(requireContext(), R.string.clear_history_label, R.string.clear_playback_history_msg) {
                    override fun onConfirmButtonPressed(dialog: DialogInterface) {
                        dialog.dismiss()
                        clearHistory()
                    }
                }
                conDialog.createNewDialog().show()
            }
            else -> return false
        }
        return true
    }

    override fun updateToolbar() {
        // Not calling super, as we do not have a refresh button that could be updated
        toolbar.menu.findItem(R.id.episodes_sort).setVisible(episodes.isNotEmpty())
        toolbar.menu.findItem(R.id.filter_items).setVisible(episodes.isNotEmpty())
        toolbar.menu.findItem(R.id.clear_history_item).setVisible(episodes.isNotEmpty())
    }

    private var eventSink: Job?     = null
    private fun cancelFlowEvents() {
        eventSink?.cancel()
        eventSink = null
    }
    private fun procFlowEvents() {
        if (eventSink != null) return
        eventSink = lifecycleScope.launch {
            EventFlow.events.collectLatest { event ->
                Logd(TAG, "Received event: ${event.TAG}")
                when (event) {
                    is FlowEvent.HistoryEvent -> {
                        sortOrder = event.sortOrder
                        if (event.startDate > 0) startDate = event.startDate
                        endDate = event.endDate
                        loadItems()
                        updateToolbar()
                    }
                    else -> {}
                }
            }
        }
    }

    override fun loadData(): List<Episode> {
        allHistory = getHistory(0, Int.MAX_VALUE, startDate, endDate, sortOrder).toMutableList()
        return allHistory.subList(0, min(allHistory.size-1, page * EPISODES_PER_PAGE))
    }

    override fun loadMoreData(page: Int): List<Episode> {
        val offset = (page - 1) * EPISODES_PER_PAGE
        if (offset >= allHistory.size) return listOf()
        val toIndex = offset + EPISODES_PER_PAGE
        return allHistory.subList(offset, min(allHistory.size, toIndex))
    }

    override fun loadTotalItemCount(): Int {
        return getNumberOfCompleted().toInt()
    }

    fun clearHistory() : Job {
        Logd(TAG, "clearHistory called")
        return runOnIOScope {
            val mediaAll = realm.query(EpisodeMedia::class).find()
            for (m in mediaAll) {
                m.playbackCompletionDate = null
            }
            EventFlow.postEvent(FlowEvent.HistoryEvent())
        }
    }

    class HistorySortDialog : EpisodeSortDialog() {
        override fun onAddItem(title: Int, ascending: SortOrder, descending: SortOrder, ascendingIsDefault: Boolean) {
            if (ascending == SortOrder.DATE_OLD_NEW || ascending == SortOrder.PLAYED_DATE_OLD_NEW
                    || ascending == SortOrder.COMPLETED_DATE_OLD_NEW
                    || ascending == SortOrder.DURATION_SHORT_LONG || ascending == SortOrder.EPISODE_TITLE_A_Z
                    || ascending == SortOrder.SIZE_SMALL_LARGE || ascending == SortOrder.FEED_TITLE_A_Z) {
                super.onAddItem(title, ascending, descending, ascendingIsDefault)
            }
        }
        override fun onSelectionChanged() {
            super.onSelectionChanged()
            EventFlow.postEvent(FlowEvent.HistoryEvent(sortOrder?: SortOrder.PLAYED_DATE_NEW_OLD))
        }
    }

    companion object {
        val TAG = HistoryFragment::class.simpleName ?: "Anonymous"

        fun getNumberOfCompleted(): Long {
            Logd(TAG, "getHistoryLength called")
            return realm.query(EpisodeMedia::class).query("playbackCompletionTime > 0").count().find()
        }

        /**
         * Loads the playback history from the database. A FeedItem is in the playback history if playback of the correpsonding episode
         * has been completed at least once.
         * @param limit The maximum number of episodes to return.
         * @return The playback history. The FeedItems are sorted by their media's playbackCompletionDate in descending order.
         */
        fun getHistory(offset: Int, limit: Int, start: Long = 0L, end: Long = Date().time,
                       sortOrder: SortOrder = SortOrder.PLAYED_DATE_NEW_OLD): List<Episode> {
            Logd(TAG, "getHistory() called")
            val medias = realm.query(EpisodeMedia::class).query("lastPlayedTime > $0 AND lastPlayedTime <= $1", start, end).find()
            var episodes: MutableList<Episode> = mutableListOf()
            for (m in medias) {
                if (m.episode != null) episodes.add(m.episode!!)
            }
            getPermutor(sortOrder).reorder(episodes)
            if (episodes.size > offset) episodes = episodes.subList(offset, min(episodes.size, offset+limit))
            return realm.copyFromRealm(episodes)
        }
    }
}
