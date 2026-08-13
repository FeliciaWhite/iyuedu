package io.legado.app.ui.main.bookshelf.style1.books

import android.content.Context
import android.os.Bundle
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.legado.app.R
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.constant.BookType
import io.legado.app.data.entities.Book
import io.legado.app.databinding.ItemBookshelfListBinding
import io.legado.app.help.book.isLocal
import io.legado.app.help.config.AppConfig
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadBook
import io.legado.app.service.BaseReadAloudService
import io.legado.app.utils.invisible
import io.legado.app.utils.startActivityForBook
import io.legado.app.utils.visible
import io.legado.app.utils.toTimeAgo
import kotlinx.coroutines.launch
import splitties.views.onLongClick

class BooksAdapterList(
    context: Context,
    private val fragment: Fragment,
    private val callBack: CallBack,
    private val lifecycle: Lifecycle
) : BaseBooksAdapter<ItemBookshelfListBinding>(context) {

    init {
        // 朗读启动/停止时刷新书架列表，使耳机按钮实时切换为停止按钮
        fragment.lifecycleScope.launch {
            fragment.repeatOnLifecycle(Lifecycle.State.STARTED) {
                BaseReadAloudService.isRunFlow.collect {
                    notifyItemRangeChanged(0, itemCount)
                }
            }
        }
    }

    override fun getViewBinding(parent: ViewGroup): ItemBookshelfListBinding {
        return ItemBookshelfListBinding.inflate(inflater, parent, false)
    }

    override fun convert(
        holder: ItemViewHolder,
        binding: ItemBookshelfListBinding,
        item: Book,
        payloads: MutableList<Any>
    ) = binding.run {
        if (payloads.isEmpty()) {
            tvName.text = item.name
            tvAuthor.text = item.author
            tvRead.text = item.durChapterTitle
            tvLast.text = item.latestChapterTitle
            ivCover.load(item, false)
            // 仅支持朗读的书（非音频/图片/视频）显示朗读按钮
            val canReadAloud =
                item.type and (BookType.audio or BookType.image or BookType.video) == 0
            ivReadAloud.visible(canReadAloud)
            ivReadAloudTouch.visible(canReadAloud)
            // 朗读中且正在朗读本书时，角标显示为“停止”图标
            if (canReadAloud && isReadAloud(item)) {
                ivReadAloud.setImageResource(R.drawable.ic_stop_black_24dp)
            } else {
                ivReadAloud.setImageResource(R.drawable.ic_read_aloud)
            }
            upRefresh(binding, item)
            upLastUpdateTime(binding, item)
        } else {
            for (i in payloads.indices) {
                val bundle = payloads[i] as Bundle
                bundle.keySet().forEach {
                    when (it) {
                        "name" -> tvName.text = item.name
                        "author" -> tvAuthor.text = item.author
                        "dur" -> tvRead.text = item.durChapterTitle
                        "last" -> tvLast.text = item.latestChapterTitle
                        "cover" -> ivCover.load(
                            item,
                            false,
                            fragment,
                            lifecycle
                        )

                        "refresh" -> upRefresh(binding, item)
                        "lastUpdateTime" -> upLastUpdateTime(binding, item)
                    }
                }
            }
        }
    }

    /** 该书是否正在被朗读 */
    private fun isReadAloud(item: Book): Boolean {
        return BaseReadAloudService.isRun && ReadBook.book?.bookUrl == item.bookUrl
    }

    private fun upRefresh(binding: ItemBookshelfListBinding, item: Book) {
        if (!item.isLocal && callBack.isUpdate(item.bookUrl)) {
            binding.bvUnread.invisible()
            binding.rlLoading.visible()
        } else {
            binding.rlLoading.gone()
            if (AppConfig.showUnread) {
                binding.bvUnread.setHighlight(item.lastCheckCount > 0)
                binding.bvUnread.setBadgeCount(item.getUnreadChapterNum())
            } else {
                binding.bvUnread.invisible()
            }
        }
    }

    private fun upLastUpdateTime(binding: ItemBookshelfListBinding, item: Book) {
        if (AppConfig.showLastUpdateTime && !item.isLocal) {
            val time = item.latestChapterTime.toTimeAgo()
            if (binding.tvLastUpdateTime.text != time) {
                binding.tvLastUpdateTime.text = time
            }
        } else {
            binding.tvLastUpdateTime.text = ""
        }
    }

    override fun registerListener(holder: ItemViewHolder, binding: ItemBookshelfListBinding) {
        holder.itemView.apply {
            setOnClickListener {
                getItem(holder.bindingAdapterPosition)?.let {
                    callBack.open(it)
                }
            }

            onLongClick {
                getItem(holder.bindingAdapterPosition)?.let {
                    callBack.openBookInfo(it)
                }
            }
        }

        // 覆盖整个封面的点击区域：朗读中则停止，否则打开该书并直接开始朗读
        binding.ivReadAloudTouch.setOnClickListener {
            getItem(holder.bindingAdapterPosition)?.let { book ->
                if (isReadAloud(book)) {
                    ReadAloud.stop(context)
                } else {
                    context.startActivityForBook(book) {
                        putExtra("readAloud", true)
                    }
                }
            }
        }
    }
}
