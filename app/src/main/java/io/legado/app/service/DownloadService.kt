package io.legado.app.service

import android.content.Intent
import android.os.Handler
import androidx.core.app.NotificationCompat
import io.legado.app.App
import io.legado.app.R
import io.legado.app.base.BaseService
import io.legado.app.constant.AppConst
import io.legado.app.constant.EventBus
import io.legado.app.constant.IntentAction
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.AppConfig
import io.legado.app.help.BookHelp
import io.legado.app.help.IntentHelp
import io.legado.app.help.coroutine.CompositeCoroutine
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.model.WebBook
import io.legado.app.service.help.Download
import io.legado.app.utils.postEvent
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.isActive
import org.jetbrains.anko.toast
import java.util.concurrent.Executors

class DownloadService : BaseService() {
    private val threadCount = AppConfig.threadCount
    private var searchPool =
        Executors.newFixedThreadPool(threadCount).asCoroutineDispatcher()
    private var tasks = CompositeCoroutine()
    private val handler = Handler()
    private var runnable: Runnable = Runnable { upDownload() }
    private val bookMap = hashMapOf<String, Book>()
    private val webBookMap = hashMapOf<String, WebBook>()
    private val downloadMap = hashMapOf<String, LinkedHashSet<BookChapter>>()
    private val downloadCount = hashMapOf<String, DownloadCount>()
    private val finalMap = hashMapOf<String, LinkedHashSet<BookChapter>>()
    private val downloadingList = arrayListOf<String>()

    @Volatile
    private var downloadingCount = 0
    private var notificationContent = "正在启动下载"

    private val notificationBuilder by lazy {
        val builder = NotificationCompat.Builder(this, AppConst.channelIdDownload)
            .setSmallIcon(R.drawable.ic_download)
            .setOngoing(true)
            .setContentTitle(getString(R.string.download_offline))
        builder.addAction(
            R.drawable.ic_stop_black_24dp,
            getString(R.string.cancel),
            IntentHelp.servicePendingIntent<DownloadService>(this, IntentAction.stop)
        )
        builder.setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
    }

    override fun onCreate() {
        super.onCreate()
        updateNotification(notificationContent)
        handler.postDelayed(runnable, 1000)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.action?.let { action ->
            when (action) {
                IntentAction.start -> addDownloadData(
                    intent.getStringExtra("bookUrl"),
                    intent.getIntExtra("start", 0),
                    intent.getIntExtra("end", 0)
                )
                IntentAction.remove -> removeDownload(intent.getStringExtra("bookUrl"))
                IntentAction.stop -> stopDownload()
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onDestroy() {
        tasks.clear()
        searchPool.close()
        handler.removeCallbacks(runnable)
        downloadMap.clear()
        finalMap.clear()
        super.onDestroy()
        postEvent(EventBus.UP_DOWNLOAD, downloadMap)
    }

    private fun getBook(bookUrl: String): Book? {
        var book = bookMap[bookUrl]
        if (book == null) {
            synchronized(this) {
                book = bookMap[bookUrl]
                if (book == null) {
                    book = App.db.bookDao().getBook(bookUrl)
                    if (book == null) {
                        removeDownload(bookUrl)
                    }
                }
            }
        }
        return book
    }

    private fun getWebBook(bookUrl: String, origin: String): WebBook? {
        var webBook = webBookMap[origin]
        if (webBook == null) {
            synchronized(this) {
                webBook = webBookMap[origin]
                if (webBook == null) {
                    App.db.bookSourceDao().getBookSource(origin)?.let {
                        webBook = WebBook(it)
                    }
                    if (webBook == null) {
                        removeDownload(bookUrl)
                    }
                }
            }
        }
        return webBook
    }

    private fun addDownloadData(bookUrl: String?, start: Int, end: Int) {
        bookUrl ?: return
        if (downloadMap.containsKey(bookUrl)) {
            toast("该书已在下载列表")
            return
        }
        downloadCount[bookUrl] = DownloadCount()
        execute {
            App.db.bookChapterDao().getChapterList(bookUrl, start, end).let {
                if (it.isNotEmpty()) {
                    val chapters = linkedSetOf<BookChapter>()
                    chapters.addAll(it)
                    downloadMap[bookUrl] = chapters
                }
            }
            for (i in 0 until threadCount) {
                if (downloadingCount < threadCount) {
                    download()
                }
            }
        }
    }

    private fun removeDownload(bookUrl: String?) {
        downloadMap.remove(bookUrl)
        finalMap.remove(bookUrl)
    }

    private fun download() {
        downloadingCount += 1
        tasks.add(Coroutine.async(this, context = searchPool) {
            if (!isActive) return@async
            val bookChapter: BookChapter? = synchronized(this@DownloadService) {
                downloadMap.forEach {
                    it.value.forEach { chapter ->
                        if (!downloadingList.contains(chapter.url)) {
                            downloadingList.add(chapter.url)
                            return@synchronized chapter
                        }
                    }
                }
                return@synchronized null
            }
            if (bookChapter == null) {
                postDownloading(false)
            } else {
                val book = getBook(bookChapter.bookUrl)
                if (book == null) {
                    postDownloading(true)
                    return@async
                }
                val webBook = getWebBook(bookChapter.bookUrl, book.origin)
                if (webBook == null) {
                    postDownloading(true)
                    return@async
                }
                if (!BookHelp.hasContent(book, bookChapter)) {
                    webBook.getContent(
                        book,
                        bookChapter,
                        scope = this,
                        context = searchPool
                    ).onError {
                        synchronized(this) {
                            downloadingList.remove(bookChapter.url)
                        }
                        Download.addLog(it.localizedMessage)
                    }.onSuccess(IO) { content ->
                        BookHelp.saveContent(book, bookChapter, content)
                        synchronized(this@DownloadService) {
                            downloadCount[book.bookUrl]?.increaseSuccess()
                            downloadCount[book.bookUrl]?.increaseFinished()
                            downloadCount[book.bookUrl]?.let {
                                updateNotification(
                                    it,
                                    downloadMap[book.bookUrl]?.size,
                                    bookChapter.title
                                )
                            }
                            val chapterMap =
                                finalMap[book.bookUrl]
                                    ?: linkedSetOf<BookChapter>().apply {
                                        finalMap[book.bookUrl] = this
                                    }
                            chapterMap.add(bookChapter)
                            if (chapterMap.size == downloadMap[book.bookUrl]?.size) {
                                downloadMap.remove(book.bookUrl)
                                finalMap.remove(book.bookUrl)
                                downloadCount.remove(book.bookUrl)
                            }
                        }
                    }.onFinally(IO) {
                        postDownloading(true)
                    }
                } else {
                    //无需下载的，设置为增加成功
                    downloadCount[book.bookUrl]?.increaseSuccess()
                    downloadCount[book.bookUrl]?.increaseFinished()
                    postDownloading(true)
                }
            }
        })
    }

    private fun postDownloading(hasChapter: Boolean) {
        downloadingCount -= 1
        if (hasChapter) {
            download()
        } else {
            if (downloadingCount < 1) {
                stopDownload()
            }
        }
    }

    private fun stopDownload() {
        tasks.clear()
        stopSelf()
    }

    private fun upDownload() {
        updateNotification(notificationContent)
        postEvent(EventBus.UP_DOWNLOAD, downloadMap)
        handler.removeCallbacks(runnable)
        handler.postDelayed(runnable, 1000)
    }

    private fun updateNotification(
        downloadCount: DownloadCount,
        totalCount: Int?,
        content: String
    ) {
        notificationContent =
            "进度:${downloadCount.downloadFinishedCount}/$totalCount,成功:${downloadCount.successCount},$content"
    }

    /**
     * 更新通知
     */
    private fun updateNotification(content: String) {
        val builder = notificationBuilder
        builder.setContentText(content)
        val notification = builder.build()
        startForeground(AppConst.notificationIdDownload, notification)
    }


    class DownloadCount {
        @Volatile
        var downloadFinishedCount = 0 // 下载完成的条目数量

        @Volatile
        var successCount = 0 //下载成功的条目数量

        fun increaseSuccess() {
            ++successCount
        }

        fun increaseFinished() {
            ++downloadFinishedCount
        }
    }
}