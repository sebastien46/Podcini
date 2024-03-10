package ac.mdiq.podcini.ui.fragment

import ac.mdiq.podcini.ui.activity.MainActivity
import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.LightingColorFilter
import android.os.Bundle
import android.util.Log
import android.view.*
import android.widget.AdapterView
import android.widget.Toast
import androidx.appcompat.content.res.AppCompatResources
import androidx.appcompat.widget.Toolbar
import androidx.fragment.app.Fragment
import androidx.media3.common.util.UnstableApi
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.request.RequestOptions
import com.google.android.material.snackbar.Snackbar
import com.joanzapata.iconify.Iconify
import com.leinardi.android.speeddial.SpeedDialActionItem
import com.leinardi.android.speeddial.SpeedDialView
import ac.mdiq.podcini.R
import ac.mdiq.podcini.ui.adapter.EpisodeItemListAdapter
import ac.mdiq.podcini.ui.adapter.SelectableAdapter
import ac.mdiq.podcini.feed.FeedEvent
import ac.mdiq.podcini.ui.menuhandler.MenuItemUtils
import ac.mdiq.podcini.storage.DBReader
import ac.mdiq.podcini.storage.DBWriter
import ac.mdiq.podcini.util.FeedItemPermutors
import ac.mdiq.podcini.util.FeedItemUtil
import ac.mdiq.podcini.util.IntentUtils
import ac.mdiq.podcini.util.ShareUtils
import ac.mdiq.podcini.net.download.FeedUpdateManager
import ac.mdiq.podcini.ui.gui.MoreContentListFooterUtil
import ac.mdiq.podcini.databinding.FeedItemListFragmentBinding
import ac.mdiq.podcini.databinding.MultiSelectSpeedDialBinding
import ac.mdiq.podcini.ui.dialog.*
import ac.mdiq.podcini.util.event.*
import ac.mdiq.podcini.playback.event.PlaybackPositionEvent
import ac.mdiq.podcini.ui.fragment.actions.EpisodeMultiSelectActionHandler
import ac.mdiq.podcini.ui.fragment.swipeactions.SwipeActions
import ac.mdiq.podcini.ui.menuhandler.FeedItemMenuHandler
import ac.mdiq.podcini.storage.model.download.DownloadResult
import ac.mdiq.podcini.storage.model.feed.Feed
import ac.mdiq.podcini.storage.model.feed.FeedItem
import ac.mdiq.podcini.storage.model.feed.FeedItemFilter
import ac.mdiq.podcini.storage.model.feed.SortOrder
import ac.mdiq.podcini.preferences.UserPreferences
import ac.mdiq.podcini.ui.glide.FastBlurTransformation
import ac.mdiq.podcini.ui.view.ToolbarIconTintManager
import ac.mdiq.podcini.ui.view.viewholder.EpisodeItemViewHolder
import io.reactivex.Maybe
import io.reactivex.Observable
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.Disposable
import io.reactivex.schedulers.Schedulers
import org.apache.commons.lang3.StringUtils
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException


/**
 * Displays a list of FeedItems.
 */
class FeedItemlistFragment : Fragment(), AdapterView.OnItemClickListener, Toolbar.OnMenuItemClickListener,
    SelectableAdapter.OnSelectModeListener {

    private lateinit var adapter: FeedItemListAdapter
    private lateinit var swipeActions: SwipeActions
    private lateinit var viewBinding: FeedItemListFragmentBinding
    private lateinit var speedDialBinding: MultiSelectSpeedDialBinding
    private lateinit var nextPageLoader: MoreContentListFooterUtil
    
    private var displayUpArrow = false
    private var headerCreated = false
    private var feedID: Long = 0
    private var feed: Feed? = null
    private var disposable: Disposable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val args: Bundle? = arguments
        if (args != null) feedID = args.getLong(ARGUMENT_FEED_ID)
    }

    @UnstableApi override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                                           savedInstanceState: Bundle?
    ): View {
        Log.d(TAG, "fragment onCreateView")

        viewBinding = FeedItemListFragmentBinding.inflate(inflater)
        speedDialBinding = MultiSelectSpeedDialBinding.bind(viewBinding.root)
        viewBinding.toolbar.inflateMenu(R.menu.feedlist)
        viewBinding.toolbar.setOnMenuItemClickListener(this)
        viewBinding.toolbar.setOnLongClickListener {
            viewBinding.recyclerView.scrollToPosition(5)
            viewBinding.recyclerView.post { viewBinding.recyclerView.smoothScrollToPosition(0) }
            viewBinding.appBar.setExpanded(true)
            false
        }
        displayUpArrow = parentFragmentManager.backStackEntryCount != 0
        if (savedInstanceState != null) {
            displayUpArrow = savedInstanceState.getBoolean(KEY_UP_ARROW)
        }
        (activity as MainActivity).setupToolbarToggle(viewBinding.toolbar, displayUpArrow)
        updateToolbar()

        viewBinding.recyclerView.setRecycledViewPool((activity as MainActivity).recycledViewPool)
        adapter = FeedItemListAdapter(activity as MainActivity)
        adapter.setOnSelectModeListener(this)
        viewBinding.recyclerView.adapter = adapter
        swipeActions = SwipeActions(this, TAG).attachTo(viewBinding.recyclerView)
        viewBinding.progressBar.visibility = View.VISIBLE

        val iconTintManager: ToolbarIconTintManager = object : ToolbarIconTintManager(
            requireContext(), viewBinding.toolbar, viewBinding.collapsingToolbar) {
            override fun doTint(themedContext: Context) {
                viewBinding.toolbar.menu.findItem(R.id.refresh_item)
                    .setIcon(AppCompatResources.getDrawable(themedContext, R.drawable.ic_refresh))
                viewBinding.toolbar.menu.findItem(R.id.action_search)
                    .setIcon(AppCompatResources.getDrawable(themedContext, R.drawable.ic_search))
            }
        }
        iconTintManager.updateTint()
        viewBinding.appBar.addOnOffsetChangedListener(iconTintManager)

        nextPageLoader = MoreContentListFooterUtil(viewBinding.moreContent.moreContentListFooter)
        nextPageLoader.setClickListener(object : MoreContentListFooterUtil.Listener {
            override fun onClick() {
                if (feed != null) {
                    FeedUpdateManager.runOnce(requireContext(), feed, true)
                }
            }
        })
        viewBinding.recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(view: RecyclerView, deltaX: Int, deltaY: Int) {
                super.onScrolled(view, deltaX, deltaY)
                val hasMorePages = feed != null && feed!!.isPaged && feed!!.nextPageLink != null
                val pageLoaderVisible = viewBinding.recyclerView.isScrolledToBottom && hasMorePages
                nextPageLoader.root.visibility = if (pageLoaderVisible) View.VISIBLE else View.GONE
                viewBinding.recyclerView.setPadding(
                    viewBinding.recyclerView.paddingLeft, 0, viewBinding.recyclerView.paddingRight,
                    if (pageLoaderVisible) nextPageLoader.root.measuredHeight else 0)
            }
        })

        EventBus.getDefault().register(this)

        viewBinding.swipeRefresh.setDistanceToTriggerSync(resources.getInteger(R.integer.swipe_refresh_distance))
        viewBinding.swipeRefresh.setOnRefreshListener {
            FeedUpdateManager.runOnceOrAsk(requireContext(), feed)
        }

        loadItems()

        // Init action UI (via a FAB Speed Dial)
        speedDialBinding.fabSD.overlayLayout = speedDialBinding.fabSDOverlay
        speedDialBinding.fabSD.inflate(R.menu.episodes_apply_action_speeddial)
        speedDialBinding.fabSD.setOnChangeListener(object : SpeedDialView.OnChangeListener {
            override fun onMainActionSelected(): Boolean {
                return false
            }

            override fun onToggleChanged(open: Boolean) {
                if (open && adapter.selectedCount == 0) {
                    (activity as MainActivity).showSnackbarAbovePlayer(R.string.no_items_selected,
                        Snackbar.LENGTH_SHORT)
                    speedDialBinding.fabSD.close()
                }
            }
        })
        speedDialBinding.fabSD.setOnActionSelectedListener { actionItem: SpeedDialActionItem ->
            EpisodeMultiSelectActionHandler((activity as MainActivity), actionItem.id)
                .handleAction(adapter.selectedItems.filterIsInstance<FeedItem>())
            adapter.endSelectMode()
            true
        }
        return viewBinding.root
    }

    override fun onDestroyView() {
        super.onDestroyView()

        EventBus.getDefault().unregister(this)
        disposable?.dispose()
        adapter.endSelectMode()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(KEY_UP_ARROW, displayUpArrow)
        super.onSaveInstanceState(outState)
    }

    private fun updateToolbar() {
        if (feed == null) {
            return
        }
        viewBinding.toolbar.menu.findItem(R.id.visit_website_item).setVisible(feed!!.link != null)
        viewBinding.toolbar.menu.findItem(R.id.refresh_complete_item).setVisible(feed!!.isPaged)
        if (StringUtils.isBlank(feed!!.link)) {
            viewBinding.toolbar.menu.findItem(R.id.visit_website_item).setVisible(false)
        }
        if (feed!!.isLocalFeed) {
            viewBinding.toolbar.menu.findItem(R.id.share_item).setVisible(false)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val horizontalSpacing = resources.getDimension(R.dimen.additional_horizontal_spacing).toInt()
        viewBinding.header.headerContainer.setPadding(
            horizontalSpacing, viewBinding.header.headerContainer.paddingTop,
            horizontalSpacing, viewBinding.header.headerContainer.paddingBottom)
    }

    @UnstableApi override fun onMenuItemClick(item: MenuItem): Boolean {
        if (feed == null) {
            (activity as MainActivity).showSnackbarAbovePlayer(
                R.string.please_wait_for_data, Toast.LENGTH_LONG)
            return true
        }
        when (item.itemId) {
            R.id.visit_website_item -> {
                if (feed!!.link != null) IntentUtils.openInBrowser(requireContext(), feed!!.link!!)
            }
            R.id.share_item -> {
                ShareUtils.shareFeedLink(requireContext(), feed!!)
            }
            R.id.refresh_item -> {
                FeedUpdateManager.runOnceOrAsk(requireContext(), feed)
            }
            R.id.refresh_complete_item -> {
                Thread {
                    feed!!.nextPageLink = feed!!.download_url
                    feed!!.pageNr = 0
                    try {
                        DBWriter.resetPagedFeedPage(feed).get()
                        FeedUpdateManager.runOnce(requireContext(), feed)
                    } catch (e: ExecutionException) {
                        throw RuntimeException(e)
                    } catch (e: InterruptedException) {
                        throw RuntimeException(e)
                    }
                }.start()
            }
            R.id.sort_items -> {
                SingleFeedSortDialog.newInstance(feed!!).show(childFragmentManager, "SortDialog")
            }
            R.id.rename_item -> {
                RenameItemDialog(activity as Activity, feed).show()
            }
            R.id.remove_feed -> {
                RemoveFeedDialog.show(requireContext(), feed!!) {
                    (activity as MainActivity).loadFragment(UserPreferences.defaultPage, null)
                    // Make sure fragment is hidden before actually starting to delete
                    requireActivity().supportFragmentManager.executePendingTransactions()
                }
            }
            R.id.action_search -> {
                (activity as MainActivity).loadChildFragment(SearchFragment.newInstance(feed!!.id, feed!!.title))
            }
            else -> {
                return false
            }
        }
        return true
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

    @UnstableApi override fun onItemClick(parent: AdapterView<*>?, view: View, position: Int, id: Long) {
        val activity: MainActivity = activity as MainActivity
        if (feed != null) {
            val ids: LongArray = FeedItemUtil.getIds(feed!!.items)
            activity.loadChildFragment(ItemPagerFragment.newInstance(ids, position))
        }
    }

    @UnstableApi @Subscribe(threadMode = ThreadMode.MAIN)
    fun onEvent(event: FeedEvent) {
        Log.d(TAG, "onEvent() called with: event = [$event]")
        if (event.feedId == feedID) {
            loadItems()
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onEventMainThread(event: FeedItemEvent) {
        Log.d(TAG, "onEventMainThread() called with: event = [$event]")
        if (feed == null || feed!!.items.isEmpty()) {
            return
        }
        var i = 0
        val size: Int = event.items.size
        while (i < size) {
            val item: FeedItem = event.items[i]
            val pos: Int = FeedItemUtil.indexOfItemWithId(feed!!.items, item.id)
            if (pos >= 0) {
                feed?.items?.removeAt(pos)
                feed?.items?.add(pos, item)
                adapter.notifyItemChangedCompat(pos)
            }
            i++
        }
    }

    @Subscribe(sticky = true, threadMode = ThreadMode.MAIN)
    fun onEventMainThread(event: EpisodeDownloadEvent) {
        Log.d(TAG, "onEventMainThread() called with: event = [$event]")
        if (feed == null || feed!!.items.isEmpty()) {
            return
        }
        for (downloadUrl in event.urls) {
            val pos: Int = FeedItemUtil.indexOfItemWithDownloadUrl(feed!!.items, downloadUrl)
            if (pos >= 0) {
                adapter.notifyItemChangedCompat(pos)
            }
        }
    }

    @UnstableApi @Subscribe(threadMode = ThreadMode.MAIN)
    fun onEventMainThread(event: PlaybackPositionEvent) {
        Log.d(TAG, "onEventMainThread() called with: event = [$event]")
        for (i in 0 until adapter.itemCount) {
            val holder: EpisodeItemViewHolder? =
                viewBinding.recyclerView.findViewHolderForAdapterPosition(i) as? EpisodeItemViewHolder
            if (holder != null && holder.isCurrentlyPlayingItem) {
                holder.notifyPlaybackPositionUpdated(event)
                break
            }
        }
    }

    @UnstableApi @Subscribe(threadMode = ThreadMode.MAIN)
    fun favoritesChanged(event: FavoritesEvent?) {
        Log.d(TAG, "favoritesChanged called")
        updateUi()
    }

    @UnstableApi @Subscribe(threadMode = ThreadMode.MAIN)
    fun onQueueChanged(event: QueueEvent?) {
        Log.d(TAG, "onQueueChanged called")
        updateUi()
    }

    override fun onStartSelectMode() {
        swipeActions.detach()
        if (feed != null && feed!!.isLocalFeed) {
            speedDialBinding.fabSD.removeActionItemById(R.id.download_batch)
        }
//        speedDialBinding.fabSD.removeActionItemById(R.id.remove_all_inbox_item)
        speedDialBinding.fabSD.visibility = View.VISIBLE
        updateToolbar()
    }

    override fun onEndSelectMode() {
        speedDialBinding.fabSD.close()
        speedDialBinding.fabSD.visibility = View.GONE
        swipeActions.attachTo(viewBinding.recyclerView)
    }

    @UnstableApi private fun updateUi() {
        loadItems()
    }

    @UnstableApi @Subscribe(threadMode = ThreadMode.MAIN)
    fun onPlayerStatusChanged(event: PlayerStatusEvent?) {
        Log.d(TAG, "onPlayerStatusChanged called")
        updateUi()
    }

    @UnstableApi @Subscribe(threadMode = ThreadMode.MAIN)
    fun onUnreadItemsChanged(event: UnreadItemsUpdateEvent?) {
        Log.d(TAG, "onUnreadItemsChanged called")
        updateUi()
    }

    @UnstableApi @Subscribe(threadMode = ThreadMode.MAIN)
    fun onFeedListChanged(event: FeedListUpdateEvent) {
        if (feed != null && event.contains(feed!!)) {
            Log.d(TAG, "onFeedListChanged called")
            updateUi()
        }
    }

    @Subscribe(sticky = true, threadMode = ThreadMode.MAIN)
    fun onEventMainThread(event: FeedUpdateRunningEvent) {
        nextPageLoader.setLoadingState(event.isFeedUpdateRunning)
        if (!event.isFeedUpdateRunning) {
            nextPageLoader.root.visibility = View.GONE
        }
        viewBinding.swipeRefresh.isRefreshing = event.isFeedUpdateRunning
    }

    @UnstableApi private fun refreshHeaderView() {
        setupHeaderView()
        if (feed == null) {
            Log.e(TAG, "Unable to refresh header view")
            return
        }
        loadFeedImage()
        if (feed!!.hasLastUpdateFailed()) {
            viewBinding.header.txtvFailure.visibility = View.VISIBLE
        } else {
            viewBinding.header.txtvFailure.visibility = View.GONE
        }
        if (feed!!.preferences != null && !feed!!.preferences!!.keepUpdated) {
            viewBinding.header.txtvUpdatesDisabled.text = ("{md-pause-circle-outline} "
                    + this.getString(R.string.updates_disabled_label))
            Iconify.addIcons(viewBinding.header.txtvUpdatesDisabled)
            viewBinding.header.txtvUpdatesDisabled.visibility = View.VISIBLE
        } else {
            viewBinding.header.txtvUpdatesDisabled.visibility = View.GONE
        }
        viewBinding.header.txtvTitle.text = feed!!.title
        viewBinding.header.txtvAuthor.text = feed!!.author
        if (feed != null && feed!!.itemFilter != null) {
            val filter: FeedItemFilter? = feed!!.itemFilter
            if (filter != null && filter.values.isNotEmpty()) {
                viewBinding.header.txtvInformation.text = ("{md-info-outline} " + this.getString(R.string.filtered_label))
                Iconify.addIcons(viewBinding.header.txtvInformation)
                viewBinding.header.txtvInformation.setOnClickListener {
                    FeedItemFilterDialog.newInstance(feed!!).show(childFragmentManager, null)
                }
                viewBinding.header.txtvInformation.visibility = View.VISIBLE
            } else {
                viewBinding.header.txtvInformation.visibility = View.GONE
            }
        } else {
            viewBinding.header.txtvInformation.visibility = View.GONE
        }
    }

    @UnstableApi private fun setupHeaderView() {
        if (feed == null || headerCreated) return

        // https://github.com/bumptech/glide/issues/529
        viewBinding.imgvBackground.colorFilter = LightingColorFilter(-0x99999a, 0x000000)
//        viewBinding.header.butShowInfo.setOnClickListener { showFeedInfo() }
        viewBinding.header.imgvCover.setOnClickListener { showFeedInfo() }
        viewBinding.header.butShowSettings.setOnClickListener {
            if (feed != null) {
                val fragment = FeedSettingsFragment.newInstance(feed!!)
                (activity as MainActivity).loadChildFragment(fragment, TransitionEffect.SLIDE)
            }
        }
        viewBinding.header.butFilter.setOnClickListener {
            if (feed != null) FeedItemFilterDialog.newInstance(feed!!).show(childFragmentManager, null)
        }
        viewBinding.header.txtvFailure.setOnClickListener { showErrorDetails() }
        viewBinding.header.counts.text = adapter.itemCount.toString()
        headerCreated = true
    }

    private fun showErrorDetails() {
        Maybe.fromCallable<DownloadResult>(
            Callable {
                val feedDownloadLog: List<DownloadResult> = DBReader.getFeedDownloadLog(feedID)
                if (feedDownloadLog.isEmpty() || feedDownloadLog[0].isSuccessful) {
                    return@Callable null
                }
                feedDownloadLog[0]
            })
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(
                { downloadStatus: DownloadResult ->
                    DownloadLogDetailsDialog(
                        requireContext(), downloadStatus).show()
                },
                { error: Throwable -> error.printStackTrace() },
                { DownloadLogFragment().show(childFragmentManager, null) })
    }

    @UnstableApi private fun showFeedInfo() {
        if (feed != null) {
            val fragment = FeedInfoFragment.newInstance(feed!!)
            (activity as MainActivity).loadChildFragment(fragment, TransitionEffect.SLIDE)
        }
    }

    private fun loadFeedImage() {
        if (feed == null) return
        Glide.with(this)
            .load(feed!!.imageUrl)
            .apply(RequestOptions()
                .placeholder(R.color.image_readability_tint)
                .error(R.color.image_readability_tint)
                .transform(FastBlurTransformation())
                .dontAnimate())
            .into(viewBinding.imgvBackground)

        Glide.with(this)
            .load(feed!!.imageUrl)
            .apply(RequestOptions()
                .placeholder(R.color.light_gray)
                .error(R.color.light_gray)
                .fitCenter()
                .dontAnimate())
            .into(viewBinding.header.imgvCover)
    }

    @UnstableApi private fun loadItems() {
        disposable?.dispose()

        disposable = Observable.fromCallable<Feed?> { this.loadData() }
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(
                { result: Feed? ->
                    feed = result
                    Log.d(TAG, "loadItems subscribe called ${feed?.title}")
                    swipeActions.setFilter(feed?.itemFilter)
                    refreshHeaderView()
                    viewBinding.progressBar.visibility = View.GONE
                    adapter.setDummyViews(0)
                    if (feed != null) adapter.updateItems(feed!!.items)
                    viewBinding.header.counts.text = (feed?.items?.size?:0).toString()
                    updateToolbar()
                }, { error: Throwable? ->
                    feed = null
                    refreshHeaderView()
                    adapter.setDummyViews(0)
                    adapter.updateItems(emptyList())
                    updateToolbar()
                    Log.e(TAG, Log.getStackTraceString(error))
                })
    }

    private fun loadData(): Feed? {
        val feed: Feed = DBReader.getFeed(feedID, true) ?: return null
//        Log.d(TAG, "loadData got feed ${feed.title} with items: ${feed.items.size} ${if (feed.items.isNotEmpty()) feed.items[0].getPubDate() else ""}")
        if (feed.items.isNotEmpty()) {
            DBReader.loadAdditionalFeedItemListData(feed.items)
            if (feed.sortOrder != null) {
                val feedItems: MutableList<FeedItem> = feed.items
                FeedItemPermutors.getPermutor(feed.sortOrder!!).reorder(feedItems)
                feed.items = feedItems
            }
        }
        return feed
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onKeyUp(event: KeyEvent) {
        if (!isAdded || !isVisible || !isMenuVisible) {
            return
        }
        when (event.keyCode) {
            KeyEvent.KEYCODE_T -> viewBinding.recyclerView.smoothScrollToPosition(0)
            KeyEvent.KEYCODE_B -> viewBinding.recyclerView.smoothScrollToPosition(adapter.itemCount - 1)
            else -> {}
        }
    }

    private inner class FeedItemListAdapter(mainActivity: MainActivity) : EpisodeItemListAdapter(mainActivity) {
        @UnstableApi override fun beforeBindViewHolder(holder: EpisodeItemViewHolder, pos: Int) {
//            holder.coverHolder.visibility = View.GONE
        }

        @UnstableApi override fun onCreateContextMenu(menu: ContextMenu, v: View, menuInfo: ContextMenu.ContextMenuInfo?) {
            super.onCreateContextMenu(menu, v, menuInfo)
//            if (!inActionMode()) {
//                menu.findItem(R.id.multi_select).setVisible(true)
//            }
            MenuItemUtils.setOnClickListeners(menu) { item: MenuItem ->
                this@FeedItemlistFragment.onContextItemSelected(item)
            }
        }
    }

    class SingleFeedSortDialog : ItemSortDialog() {
        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            sortOrder = SortOrder.fromCodeString(arguments?.getString(ARG_SORT_ORDER))
        }

        override fun onAddItem(title: Int,
                                         ascending: SortOrder,
                                         descending: SortOrder,
                                         ascendingIsDefault: Boolean
        ) {
            if (ascending == SortOrder.DATE_OLD_NEW || ascending == SortOrder.DURATION_SHORT_LONG || ascending == SortOrder.EPISODE_TITLE_A_Z || (requireArguments().getBoolean(
                        ARG_FEED_IS_LOCAL) && ascending == SortOrder.EPISODE_FILENAME_A_Z)) {
                super.onAddItem(title, ascending, descending, ascendingIsDefault)
            }
        }

        @UnstableApi override fun onSelectionChanged() {
            super.onSelectionChanged()
            DBWriter.setFeedItemSortOrder(requireArguments().getLong(ARG_FEED_ID), sortOrder)
        }

        companion object {
            private const val ARG_FEED_ID = "feedId"
            private const val ARG_FEED_IS_LOCAL = "isLocal"
            private const val ARG_SORT_ORDER = "sortOrder"

            fun newInstance(feed: Feed): SingleFeedSortDialog {
                val bundle = Bundle()
                bundle.putLong(ARG_FEED_ID, feed.id)
                bundle.putBoolean(ARG_FEED_IS_LOCAL, feed.isLocalFeed)
                if (feed.sortOrder == null) {
                    bundle.putString(ARG_SORT_ORDER, SortOrder.DATE_NEW_OLD.code.toString())
                } else {
                    bundle.putString(ARG_SORT_ORDER, feed.sortOrder!!.code.toString())
                }
                val dialog = SingleFeedSortDialog()
                dialog.arguments = bundle
                return dialog
            }
        }
    }

    companion object {
        const val TAG: String = "ItemlistFragment"
        private const val ARGUMENT_FEED_ID = "argument.ac.mdiq.podcini.feed_id"
        private const val KEY_UP_ARROW = "up_arrow"

        /**
         * Creates new ItemlistFragment which shows the Feeditems of a specific
         * feed. Sets 'showFeedtitle' to false
         *
         * @param feedId The id of the feed to show
         * @return the newly created instance of an ItemlistFragment
         */
        @JvmStatic
        fun newInstance(feedId: Long): FeedItemlistFragment {
            val i = FeedItemlistFragment()
            val b = Bundle()
            b.putLong(ARGUMENT_FEED_ID, feedId)
            i.arguments = b
            return i
        }
    }
}
