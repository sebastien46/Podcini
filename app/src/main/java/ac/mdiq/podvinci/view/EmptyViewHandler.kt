package ac.mdiq.podvinci.view

import android.content.Context
import android.database.DataSetObserver
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.annotation.DrawableRes
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.recyclerview.widget.RecyclerView
import ac.mdiq.podvinci.R

class EmptyViewHandler(context: Context?) {
    private var layoutAdded = false
    private var listAdapter: ListAdapter? = null
    private var recyclerAdapter: RecyclerView.Adapter<*>? = null

    private val emptyView: View
    private val tvTitle: TextView
    private val tvMessage: TextView
    private val ivIcon: ImageView

    fun setTitle(title: Int) {
        tvTitle.setText(title)
    }

    fun setMessage(message: Int) {
        tvMessage.setText(message)
    }

    fun setMessage(message: String?) {
        tvMessage.text = message
    }

    fun setIcon(@DrawableRes icon: Int) {
        ivIcon.setImageResource(icon)
        ivIcon.visibility = View.VISIBLE
    }

    fun hide() {
        emptyView.visibility = View.GONE
    }

    fun attachToListView(listView: AbsListView) {
        check(!layoutAdded) { "Can not attach EmptyView multiple times" }
        addToParentView(listView)
        layoutAdded = true
        listView.emptyView = emptyView
        updateAdapter(listView.adapter)
    }

    fun attachToRecyclerView(recyclerView: RecyclerView) {
        check(!layoutAdded) { "Can not attach EmptyView multiple times" }
        addToParentView(recyclerView)
        layoutAdded = true
        updateAdapter(recyclerView.adapter)
    }

    private fun addToParentView(view: View) {
        var parent = view.parent as? ViewGroup
        while (parent != null) {
            when (parent) {
                is RelativeLayout -> {
                    parent.addView(emptyView)
                    val layoutParams =
                        emptyView.layoutParams as RelativeLayout.LayoutParams
                    layoutParams.addRule(RelativeLayout.CENTER_IN_PARENT, RelativeLayout.TRUE)
                    emptyView.layoutParams = layoutParams
                    break
                }
                is FrameLayout -> {
                    parent.addView(emptyView)
                    val layoutParams =
                        emptyView.layoutParams as FrameLayout.LayoutParams
                    layoutParams.gravity = Gravity.CENTER
                    emptyView.layoutParams = layoutParams
                    break
                }
                is CoordinatorLayout -> {
                    parent.addView(emptyView)
                    val layoutParams =
                        emptyView.layoutParams as CoordinatorLayout.LayoutParams
                    layoutParams.gravity = Gravity.CENTER
                    emptyView.layoutParams = layoutParams
                    break
                }
            }
            parent = parent.parent as? ViewGroup
        }
    }

    fun updateAdapter(adapter: RecyclerView.Adapter<*>?) {
        recyclerAdapter?.unregisterAdapterDataObserver(adapterObserver)

        this.recyclerAdapter = adapter
        adapter?.registerAdapterDataObserver(adapterObserver)
        updateVisibility()
    }

    private fun updateAdapter(adapter: ListAdapter?) {
        if (this.listAdapter != null) {
            listAdapter!!.unregisterDataSetObserver(listAdapterObserver)
        }
        this.listAdapter = adapter
        adapter?.registerDataSetObserver(listAdapterObserver)
        updateVisibility()
    }

    private val adapterObserver: SimpleAdapterDataObserver = object : SimpleAdapterDataObserver() {
        override fun anythingChanged() {
            updateVisibility()
        }
    }

    private val listAdapterObserver: DataSetObserver = object : DataSetObserver() {
        override fun onChanged() {
            updateVisibility()
        }
    }

    init {
        emptyView = View.inflate(context, R.layout.empty_view_layout, null)
        tvTitle = emptyView.findViewById(R.id.emptyViewTitle)
        tvMessage = emptyView.findViewById(R.id.emptyViewMessage)
        ivIcon = emptyView.findViewById(R.id.emptyViewIcon)
    }

    fun updateVisibility() {
        val empty = if (recyclerAdapter != null) {
            recyclerAdapter!!.itemCount == 0
        } else if (listAdapter != null) {
            listAdapter!!.isEmpty
        } else {
            true
        }
        emptyView.visibility = if (empty) View.VISIBLE else View.GONE
    }
}
