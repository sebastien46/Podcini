package ac.mdiq.podcini.ui.fragment

import ac.mdiq.podcini.R
import ac.mdiq.podcini.databinding.FragmentSubscriptionsBinding
import ac.mdiq.podcini.databinding.MultiSelectSpeedDialBinding
import ac.mdiq.podcini.net.download.FeedUpdateManager
import ac.mdiq.podcini.preferences.UserPreferences
import ac.mdiq.podcini.storage.DBReader
import ac.mdiq.podcini.storage.NavDrawerData
import ac.mdiq.podcini.storage.model.feed.Feed
import ac.mdiq.podcini.ui.activity.MainActivity
import ac.mdiq.podcini.ui.adapter.SelectableAdapter
import ac.mdiq.podcini.ui.adapter.SubscriptionsRecyclerAdapter
import ac.mdiq.podcini.ui.dialog.FeedSortDialog
import ac.mdiq.podcini.ui.dialog.SubscriptionsFilterDialog
import ac.mdiq.podcini.ui.fragment.actions.FeedMultiSelectActionHandler
import ac.mdiq.podcini.ui.menuhandler.FeedMenuHandler
import ac.mdiq.podcini.ui.menuhandler.MenuItemUtils
import ac.mdiq.podcini.ui.statistics.StatisticsFragment
import ac.mdiq.podcini.ui.view.EmptyViewHandler
import ac.mdiq.podcini.ui.view.LiftOnScrollListener
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log
import android.view.*
import android.view.inputmethod.EditorInfo
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ProgressBar
import androidx.appcompat.widget.Toolbar
import androidx.fragment.app.Fragment
import androidx.media3.common.util.UnstableApi
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.leinardi.android.speeddial.SpeedDialActionItem
import com.leinardi.android.speeddial.SpeedDialView
import io.reactivex.Observable
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.Disposable
import io.reactivex.schedulers.Schedulers
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import java.util.*

/**
 * Fragment for displaying feed subscriptions
 */
class SubscriptionFragment : Fragment(), Toolbar.OnMenuItemClickListener, SelectableAdapter.OnSelectModeListener {
    private lateinit var subscriptionRecycler: RecyclerView
    private lateinit var subscriptionAdapter: SubscriptionsRecyclerAdapter
    private lateinit var emptyView: EmptyViewHandler
    private lateinit var feedsFilteredMsg: LinearLayout
    private lateinit var toolbar: MaterialToolbar
    private lateinit var swipeRefreshLayout: SwipeRefreshLayout
    private lateinit var progressBar: ProgressBar
    private lateinit var prefs: SharedPreferences
    private lateinit var speedDialView: SpeedDialView

    private val tags: MutableList<String> = mutableListOf()
    private var tagFilterIndex = 1
    private var displayedFolder: String = ""
    private var displayUpArrow = false

    private var disposable: Disposable? = null
    private var feedList: List<NavDrawerData.FeedDrawerItem> = mutableListOf()
    private var feedListFiltered: List<NavDrawerData.FeedDrawerItem> = mutableListOf()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        retainInstance = true

        prefs = requireActivity().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    @UnstableApi override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                                           savedInstanceState: Bundle?
    ): View {
        val viewBinding = FragmentSubscriptionsBinding.inflate(inflater)

        Log.d(TAG, "fragment onCreateView")
        toolbar = viewBinding.toolbar
        toolbar.setOnMenuItemClickListener(this)
        toolbar.setOnLongClickListener {
            subscriptionRecycler.scrollToPosition(5)
            subscriptionRecycler.post { subscriptionRecycler.smoothScrollToPosition(0) }
            false
        }
        displayUpArrow = parentFragmentManager.backStackEntryCount != 0
        if (savedInstanceState != null) {
            displayUpArrow = savedInstanceState.getBoolean(KEY_UP_ARROW)
        }
        (activity as MainActivity).setupToolbarToggle(toolbar, displayUpArrow)
        toolbar.inflateMenu(R.menu.subscriptions)

        if (arguments != null) {
            displayedFolder = requireArguments().getString(ARGUMENT_FOLDER, null)
            toolbar.title = displayedFolder
        }

        subscriptionRecycler = viewBinding.subscriptionsGrid
        subscriptionRecycler.addItemDecoration(SubscriptionsRecyclerAdapter.GridDividerItemDecorator())
        registerForContextMenu(subscriptionRecycler)
        subscriptionRecycler.addOnScrollListener(LiftOnScrollListener(viewBinding.appbar))
        subscriptionAdapter = object : SubscriptionsRecyclerAdapter(activity as MainActivity) {
            override fun onCreateContextMenu(menu: ContextMenu, v: View, menuInfo: ContextMenu.ContextMenuInfo?) {
                super.onCreateContextMenu(menu, v, menuInfo)
                MenuItemUtils.setOnClickListeners(menu
                ) { item: MenuItem ->
                    this@SubscriptionFragment.onContextItemSelected(item)
                }
            }
        }
        val gridLayoutManager = GridLayoutManager(context, 1, RecyclerView.VERTICAL, false)
        subscriptionRecycler.layoutManager = gridLayoutManager

        subscriptionAdapter.setOnSelectModeListener(this)
        subscriptionRecycler.adapter = subscriptionAdapter
        setupEmptyView()

        tags.add("None")
        tags.add("All")
        tags.addAll(DBReader.getTags())
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, tags)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        val catSpinner = viewBinding.categorySpinner
        catSpinner.setAdapter(adapter)
        catSpinner.setSelection(adapter.getPosition("All"))
        catSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View, position: Int, id: Long) {
                tagFilterIndex = position
                filterOnTag()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        val searchBox = viewBinding.searchBox
        searchBox.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                val text = searchBox.text.toString().lowercase(Locale.getDefault())
                val resultList = feedListFiltered.filter {
                    it.title?.lowercase(Locale.getDefault())?.contains(text)?:false ||
                            it.feed.author?.lowercase(Locale.getDefault())?.contains(text)?:false
                }
                subscriptionAdapter.setItems(resultList)
                true
            } else {
                false
            }
        }

        progressBar = viewBinding.progressBar
        progressBar.visibility = View.VISIBLE

        val subscriptionAddButton: FloatingActionButton = viewBinding.subscriptionsAdd
        subscriptionAddButton.setOnClickListener {
            if (activity is MainActivity) {
                (activity as MainActivity).loadChildFragment(AddFeedFragment())
            }
        }

        feedsFilteredMsg = viewBinding.feedsFilteredMessage
        feedsFilteredMsg.setOnClickListener {
            SubscriptionsFilterDialog().show(
                childFragmentManager, "filter")
        }

        swipeRefreshLayout = viewBinding.swipeRefresh
        swipeRefreshLayout.setDistanceToTriggerSync(resources.getInteger(R.integer.swipe_refresh_distance))
        swipeRefreshLayout.setOnRefreshListener {
            FeedUpdateManager.runOnceOrAsk(requireContext())
        }

        val speedDialBinding = MultiSelectSpeedDialBinding.bind(viewBinding.root)

        speedDialView = speedDialBinding.fabSD
        speedDialView.overlayLayout = speedDialBinding.fabSDOverlay
        speedDialView.inflate(R.menu.nav_feed_action_speeddial)
        speedDialView.setOnChangeListener(object : SpeedDialView.OnChangeListener {
            override fun onMainActionSelected(): Boolean {
                return false
            }

            override fun onToggleChanged(isOpen: Boolean) {
            }
        })
        speedDialView.setOnActionSelectedListener { actionItem: SpeedDialActionItem ->
            FeedMultiSelectActionHandler(activity as MainActivity,
                subscriptionAdapter.selectedItems.filterIsInstance<Feed>()).handleAction(actionItem.id)
            true
        }

        EventBus.getDefault().register(this)
        loadSubscriptions()

        return viewBinding.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        EventBus.getDefault().unregister(this)
        disposable?.dispose()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(KEY_UP_ARROW, displayUpArrow)
        super.onSaveInstanceState(outState)
    }

    fun filterOnTag() {
        when (tagFilterIndex) {
//            All feeds
            1 -> {
                feedListFiltered = feedList
            }
//            feeds without tag
            0 -> {
                feedListFiltered = feedList.filter {
                    it.feed.preferences?.getTags().isNullOrEmpty()
                }
            }
//            feeds with the chosen tag
            else -> {
                val tag = tags[tagFilterIndex]
                feedListFiltered = feedList.filter {
                    it.feed.preferences?.getTags()?.contains(tag) ?: false
                }
            }
        }
        subscriptionAdapter.setItems(feedListFiltered)
    }
    @Subscribe(sticky = true, threadMode = ThreadMode.MAIN)
    fun onEventMainThread(event: ac.mdiq.podcini.util.event.FeedUpdateRunningEvent) {
        swipeRefreshLayout.isRefreshing = event.isFeedUpdateRunning
    }

    @UnstableApi override fun onMenuItemClick(item: MenuItem): Boolean {
        val itemId = item.itemId
        when (itemId) {
            R.id.refresh_item -> {
                FeedUpdateManager.runOnceOrAsk(requireContext())
                return true
            }
            R.id.subscriptions_filter -> {
                SubscriptionsFilterDialog().show(childFragmentManager, "filter")
                return true
            }
            R.id.subscriptions_sort -> {
                FeedSortDialog.showDialog(requireContext())
                return true
            }
            R.id.action_search -> {
                (activity as MainActivity).loadChildFragment(SearchFragment.newInstance())
                return true
            }
            R.id.action_statistics -> {
                (activity as MainActivity).loadChildFragment(StatisticsFragment())
                return true
            }
            else -> return false
        }
    }

    private fun setupEmptyView() {
        emptyView = EmptyViewHandler(requireContext())
        emptyView.setIcon(R.drawable.ic_subscriptions)
        emptyView.setTitle(R.string.no_subscriptions_head_label)
        emptyView.setMessage(R.string.no_subscriptions_label)
        emptyView.attachToRecyclerView(subscriptionRecycler)
    }

    override fun onStop() {
        super.onStop()
        subscriptionAdapter.endSelectMode()
    }

    private fun loadSubscriptions() {
        disposable?.dispose()
        emptyView.hide()
        disposable = Observable.fromCallable {
            val data: NavDrawerData = DBReader.getNavDrawerData(UserPreferences.subscriptionsFilter)
            val items: List<NavDrawerData.FeedDrawerItem> = data.items
            items
        }
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(
                { result: List<NavDrawerData.FeedDrawerItem> ->
                    if ( feedListFiltered.size > result.size) {
                        // We have fewer items. This can result in items being selected that are no longer visible.
                        subscriptionAdapter.endSelectMode()
                    }
                    feedList = result
                    filterOnTag()
                    progressBar.visibility = View.GONE
                    subscriptionAdapter.setItems(feedListFiltered)
                    emptyView.updateVisibility()
                }, { error: Throwable? ->
                    Log.e(TAG, Log.getStackTraceString(error))
                })

        if (UserPreferences.subscriptionsFilter.isEnabled) {
            feedsFilteredMsg.visibility = View.VISIBLE
        } else {
            feedsFilteredMsg.visibility = View.GONE
        }
    }

    override fun onContextItemSelected(item: MenuItem): Boolean {
        val drawerItem: NavDrawerData.DrawerItem = subscriptionAdapter.getSelectedItem() ?: return false
        val itemId = item.itemId
//        if (drawerItem.type == NavDrawerData.DrawerItem.Type.TAG && itemId == R.id.rename_folder_item) {
//            RenameItemDialog(activity as Activity, drawerItem).show()
//            return true
//        }

        val feed: Feed = (drawerItem as NavDrawerData.FeedDrawerItem).feed
        if (itemId == R.id.multi_select) {
            speedDialView.visibility = View.VISIBLE
            return subscriptionAdapter.onContextItemSelected(item)
        }
        return FeedMenuHandler.onMenuItemClicked(this, item.itemId, feed) { this.loadSubscriptions() }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onFeedListChanged(event: ac.mdiq.podcini.util.event.FeedListUpdateEvent?) {
        loadSubscriptions()
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onUnreadItemsChanged(event: ac.mdiq.podcini.util.event.UnreadItemsUpdateEvent?) {
        loadSubscriptions()
    }

    override fun onEndSelectMode() {
        speedDialView.close()
        speedDialView.visibility = View.GONE
        subscriptionAdapter.setItems(feedListFiltered)
    }

    override fun onStartSelectMode() {
        val feedsOnly: MutableList<NavDrawerData.DrawerItem> = ArrayList<NavDrawerData.DrawerItem>()
        for (item in feedListFiltered) {
            feedsOnly.add(item)
        }
        subscriptionAdapter.setItems(feedsOnly)
    }

    companion object {
        const val TAG: String = "SubscriptionFragment"
        private const val PREFS = "SubscriptionFragment"
        private const val KEY_UP_ARROW = "up_arrow"
        private const val ARGUMENT_FOLDER = "folder"

        fun newInstance(folderTitle: String?): SubscriptionFragment {
            val fragment = SubscriptionFragment()
            val args = Bundle()
            args.putString(ARGUMENT_FOLDER, folderTitle)
            fragment.arguments = args
            return fragment
        }
    }
}