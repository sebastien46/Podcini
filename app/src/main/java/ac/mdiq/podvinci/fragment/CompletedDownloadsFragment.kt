package ac.mdiq.podvinci.fragment

import ac.mdiq.podvinci.activity.MainActivity
import android.os.Bundle
import android.util.Log
import android.view.*
import android.widget.ProgressBar
import androidx.appcompat.widget.Toolbar
import androidx.fragment.app.Fragment
import androidx.media3.common.util.UnstableApi
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.snackbar.Snackbar
import com.leinardi.android.speeddial.SpeedDialActionItem
import com.leinardi.android.speeddial.SpeedDialView
import ac.mdiq.podvinci.R
import ac.mdiq.podvinci.adapter.EpisodeItemListAdapter
import ac.mdiq.podvinci.adapter.SelectableAdapter
import ac.mdiq.podvinci.adapter.actionbutton.DeleteActionButton
import ac.mdiq.podvinci.core.event.DownloadLogEvent
import ac.mdiq.podvinci.core.menuhandler.MenuItemUtils
import ac.mdiq.podvinci.core.storage.DBReader
import ac.mdiq.podvinci.core.util.FeedItemUtil
import ac.mdiq.podvinci.core.util.download.FeedUpdateManager
import ac.mdiq.podvinci.dialog.ItemSortDialog
import ac.mdiq.podvinci.event.EpisodeDownloadEvent
import ac.mdiq.podvinci.event.FeedItemEvent
import ac.mdiq.podvinci.event.PlayerStatusEvent
import ac.mdiq.podvinci.event.UnreadItemsUpdateEvent
import ac.mdiq.podvinci.event.playback.PlaybackPositionEvent
import ac.mdiq.podvinci.fragment.actions.EpisodeMultiSelectActionHandler
import ac.mdiq.podvinci.fragment.swipeactions.SwipeActions
import ac.mdiq.podvinci.menuhandler.FeedItemMenuHandler
import ac.mdiq.podvinci.model.feed.FeedItem
import ac.mdiq.podvinci.model.feed.FeedItemFilter
import ac.mdiq.podvinci.model.feed.SortOrder
import ac.mdiq.podvinci.net.download.serviceinterface.DownloadServiceInterface
import ac.mdiq.podvinci.storage.preferences.UserPreferences
import ac.mdiq.podvinci.view.EmptyViewHandler
import ac.mdiq.podvinci.view.EpisodeItemListRecyclerView
import ac.mdiq.podvinci.view.LiftOnScrollListener
import ac.mdiq.podvinci.view.viewholder.EpisodeItemViewHolder
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import io.reactivex.Observable
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.Disposable
import io.reactivex.schedulers.Schedulers
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode

/**
 * Displays all completed downloads and provides a button to delete them.
 */
class CompletedDownloadsFragment : Fragment(), SelectableAdapter.OnSelectModeListener, Toolbar.OnMenuItemClickListener {
    private var runningDownloads: Set<String>? = HashSet()
    private var items: MutableList<FeedItem> = mutableListOf()
    
    private lateinit var adapter: CompletedDownloadsListAdapter
    private lateinit var toolbar: MaterialToolbar
    private lateinit var recyclerView: EpisodeItemListRecyclerView
    private lateinit var swipeActions: SwipeActions
    private lateinit var speedDialView: SpeedDialView
    private lateinit var progressBar: ProgressBar
    private lateinit var emptyView: EmptyViewHandler
    
    private var disposable: Disposable? = null
    private var displayUpArrow = false
    

    @UnstableApi override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                                           savedInstanceState: Bundle?
    ): View {
        val root: View = inflater.inflate(R.layout.simple_list_fragment, container, false)
        toolbar = root.findViewById(R.id.toolbar)
        toolbar.setTitle(R.string.downloads_label)
        toolbar.inflateMenu(R.menu.downloads_completed)
        toolbar.setOnMenuItemClickListener(this)
        toolbar.setOnLongClickListener { v: View? ->
            recyclerView.scrollToPosition(5)
            recyclerView.post { recyclerView.smoothScrollToPosition(0) }
            false
        }
        displayUpArrow = parentFragmentManager.backStackEntryCount != 0
        if (savedInstanceState != null) {
            displayUpArrow = savedInstanceState.getBoolean(KEY_UP_ARROW)
        }
        (activity as MainActivity).setupToolbarToggle(toolbar, displayUpArrow)

        recyclerView = root.findViewById(R.id.recyclerView)
        recyclerView.setRecycledViewPool((activity as MainActivity).recycledViewPool)
        adapter = CompletedDownloadsListAdapter(activity as MainActivity)
        adapter.setOnSelectModeListener(this)
        recyclerView.adapter = adapter
        recyclerView.addOnScrollListener(LiftOnScrollListener(root.findViewById(R.id.appbar)))
        swipeActions = SwipeActions(this, TAG).attachTo(recyclerView)
        swipeActions.setFilter(FeedItemFilter(FeedItemFilter.DOWNLOADED))

        val animator: RecyclerView.ItemAnimator? = recyclerView.itemAnimator
        if (animator is SimpleItemAnimator) {
            animator.supportsChangeAnimations = false
        }

        progressBar = root.findViewById(R.id.progLoading)
        progressBar.visibility = View.VISIBLE

        speedDialView = root.findViewById(R.id.fabSD)
        speedDialView.overlayLayout = root.findViewById(R.id.fabSDOverlay)
        speedDialView.inflate(R.menu.episodes_apply_action_speeddial)
        speedDialView.removeActionItemById(R.id.download_batch)
        speedDialView.removeActionItemById(R.id.mark_read_batch)
        speedDialView.removeActionItemById(R.id.mark_unread_batch)
        speedDialView.removeActionItemById(R.id.remove_from_queue_batch)
        speedDialView.removeActionItemById(R.id.remove_all_inbox_item)
        speedDialView.setOnChangeListener(object : SpeedDialView.OnChangeListener {
            override fun onMainActionSelected(): Boolean {
                return false
            }

            override fun onToggleChanged(open: Boolean) {
                if (open && adapter.selectedCount == 0) {
                    (activity as MainActivity).showSnackbarAbovePlayer(R.string.no_items_selected,
                        Snackbar.LENGTH_SHORT)
                    speedDialView.close()
                }
            }
        })
        speedDialView.setOnActionSelectedListener { actionItem: SpeedDialActionItem ->
            adapter.selectedItems.let {
                EpisodeMultiSelectActionHandler((activity as MainActivity), actionItem.id)
                    .handleAction(it.filterIsInstance<FeedItem>())
            }
            adapter.endSelectMode()
            true
        }
        if (arguments != null && requireArguments().getBoolean(ARG_SHOW_LOGS, false)) {
            DownloadLogFragment().show(childFragmentManager, null)
        }

        addEmptyView()
        EventBus.getDefault().register(this)
        return root
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(KEY_UP_ARROW, displayUpArrow)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroyView() {
        EventBus.getDefault().unregister(this)
        adapter.endSelectMode()
        toolbar.setOnMenuItemClickListener(null)
        toolbar.setOnLongClickListener(null)

        super.onDestroyView()
    }

    override fun onStart() {
        super.onStart()
        loadItems()
    }

    override fun onStop() {
        super.onStop()
        disposable?.dispose()
    }

    @UnstableApi override fun onMenuItemClick(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.refresh_item -> {
                FeedUpdateManager.runOnceOrAsk(requireContext())
                return true
            }
            R.id.action_download_logs -> {
                DownloadLogFragment().show(childFragmentManager, null)
                return true
            }
            R.id.action_search -> {
                (activity as MainActivity).loadChildFragment(SearchFragment.newInstance())
                return true
            }
            R.id.downloads_sort -> {
                DownloadsSortDialog().show(childFragmentManager, "SortDialog")
                return true
            }
            else -> return false
        }
    }

    @Subscribe(sticky = true, threadMode = ThreadMode.MAIN)
    fun onEventMainThread(event: EpisodeDownloadEvent) {
        val newRunningDownloads: MutableSet<String> = HashSet()
        for (url in event.urls) {
            if (DownloadServiceInterface.get()?.isDownloadingEpisode(url) == true) {
                newRunningDownloads.add(url)
            }
        }
        if (newRunningDownloads != runningDownloads) {
            runningDownloads = newRunningDownloads
            loadItems()
            return  // Refreshed anyway
        }
        for (downloadUrl in event.urls) {
            val pos = FeedItemUtil.indexOfItemWithDownloadUrl(items.toList(), downloadUrl)
            if (pos >= 0) {
                adapter.notifyItemChangedCompat(pos)
            }
        }
    }

    override fun onContextItemSelected(item: MenuItem): Boolean {
        val selectedItem: FeedItem? = adapter.longPressedItem
        if (selectedItem == null) {
            Log.i(TAG, "Selected item at current position was null, ignoring selection")
            return super.onContextItemSelected(item)
        }
        if (adapter.onContextItemSelected(item)) {
            return true
        }

        return FeedItemMenuHandler.onMenuItemClicked(this, item.itemId, selectedItem)
    }

    private fun addEmptyView() {
        emptyView = EmptyViewHandler(activity)
        emptyView.setIcon(R.drawable.ic_download)
        emptyView.setTitle(R.string.no_comp_downloads_head_label)
        emptyView.setMessage(R.string.no_comp_downloads_label)
        emptyView.attachToRecyclerView(recyclerView)
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onEventMainThread(event: FeedItemEvent) {
        Log.d(TAG, "onEventMainThread() called with: event = [$event]")

        var i = 0
        val size: Int = event.items.size
        while (i < size) {
            val item: FeedItem = event.items[i]
            val pos = FeedItemUtil.indexOfItemWithId(items.toList(), item.id)
            if (pos >= 0) {
                items.removeAt(pos)
                val media = item.media
                if (media != null && media.isDownloaded()) {
                    items.add(pos, item)
//                    adapter.notifyItemChangedCompat(pos)
                } else {
//                    adapter.notifyItemRemoved(pos)
                }
            }
            i++
        }
//        have to do this as adapter.notifyItemRemoved(pos) when pos == 0 causes crash
        if (size > 0) {
            adapter.setDummyViews(0)
            adapter.updateItems(items)
        }
    }

    @UnstableApi @Subscribe(threadMode = ThreadMode.MAIN)
    fun onEventMainThread(event: PlaybackPositionEvent) {
//        if (event == null) return
        for (i in 0 until adapter.itemCount) {
            val holder: EpisodeItemViewHolder? = recyclerView.findViewHolderForAdapterPosition(i) as? EpisodeItemViewHolder
            if (holder != null && holder.isCurrentlyPlayingItem) {
                holder.notifyPlaybackPositionUpdated(event)
                break
            }
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onPlayerStatusChanged(event: PlayerStatusEvent?) {
        loadItems()
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onDownloadLogChanged(event: DownloadLogEvent?) {
        loadItems()
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onUnreadItemsChanged(event: UnreadItemsUpdateEvent?) {
        loadItems()
    }

    private fun loadItems() {
        disposable?.dispose()

        emptyView.hide()
        disposable = Observable.fromCallable {
            val sortOrder: SortOrder? = UserPreferences.downloadsSortedOrder
            val downloadedItems: List<FeedItem> = DBReader.getEpisodes(0, Int.MAX_VALUE,
                FeedItemFilter(FeedItemFilter.DOWNLOADED), sortOrder)

            val mediaUrls: MutableList<String> = ArrayList()
            if (runningDownloads == null) {
                return@fromCallable downloadedItems
            }
            for (url in runningDownloads!!) {
                if (FeedItemUtil.indexOfItemWithDownloadUrl(downloadedItems, url) != -1) {
                    continue  // Already in list
                }
                mediaUrls.add(url)
            }
            val currentDownloads: MutableList<FeedItem> = DBReader.getFeedItemsWithUrl(mediaUrls).toMutableList()
            currentDownloads.addAll(downloadedItems)
            currentDownloads
        }
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(
                { result: List<FeedItem> ->
                    items = result.toMutableList()
                    adapter.setDummyViews(0)
                    progressBar.visibility = View.GONE
                    adapter.updateItems(result)
                }, { error: Throwable? ->
                    adapter.setDummyViews(0)
                    adapter.updateItems(emptyList())
                    Log.e(TAG, Log.getStackTraceString(error))
                })
    }

    override fun onStartSelectMode() {
        swipeActions.detach()
        speedDialView.visibility = View.VISIBLE
    }

    override fun onEndSelectMode() {
        speedDialView.close()
        speedDialView.visibility = View.GONE
        swipeActions.attachTo(recyclerView)
    }

    @UnstableApi private inner class CompletedDownloadsListAdapter(mainActivity: MainActivity) : EpisodeItemListAdapter(mainActivity) {
        @UnstableApi override fun afterBindViewHolder(holder: EpisodeItemViewHolder, pos: Int) {
            if (holder.feedItem != null && !inActionMode()) {
                if (holder.feedItem!!.isDownloaded) {
                    val item = getItem(pos) ?: return
                    val actionButton = DeleteActionButton(item)
                    actionButton.configure(holder.secondaryActionButton, holder.secondaryActionIcon, requireContext())
                }
            }
        }

        override fun onCreateContextMenu(menu: ContextMenu, v: View, menuInfo: ContextMenu.ContextMenuInfo?) {
            super.onCreateContextMenu(menu, v, menuInfo)
            if (!inActionMode()) {
                menu.findItem(R.id.multi_select).setVisible(true)
            }
            MenuItemUtils.setOnClickListeners(menu) { item: MenuItem ->
                this@CompletedDownloadsFragment.onContextItemSelected(item)
            }
        }
    }

    class DownloadsSortDialog : ItemSortDialog() {
        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            sortOrder = UserPreferences.downloadsSortedOrder
        }

        override fun onAddItem(title: Int,
                                         ascending: SortOrder,
                                         descending: SortOrder,
                                         ascendingIsDefault: Boolean
        ) {
            if (ascending == SortOrder.DATE_OLD_NEW || ascending == SortOrder.DURATION_SHORT_LONG || ascending == SortOrder.EPISODE_TITLE_A_Z || ascending == SortOrder.SIZE_SMALL_LARGE) {
                super.onAddItem(title, ascending, descending, ascendingIsDefault)
            }
        }

        override fun onSelectionChanged() {
            super.onSelectionChanged()
            UserPreferences.downloadsSortedOrder = sortOrder
            EventBus.getDefault().post(DownloadLogEvent.listUpdated())
        }
    }

    companion object {
        const val TAG: String = "DownloadsFragment"
        const val ARG_SHOW_LOGS: String = "show_logs"
        private const val KEY_UP_ARROW = "up_arrow"
    }
}
