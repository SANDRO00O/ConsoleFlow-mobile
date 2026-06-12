package space.karrarnazim.ConsoleFlow

import androidx.lifecycle.MutableLiveData
import java.util.concurrent.atomic.AtomicInteger

/**
 * Singleton state for all downloads. Thread-safe.
 * Service posts updates; Activities observe.
 */
object DownloadTracker {

    val downloads: MutableLiveData<List<DownloadItem>> = MutableLiveData(emptyList())

    private val idGen = AtomicInteger(1)
    private val lock  = Any()

    fun nextId(): Int = idGen.getAndIncrement()

    fun add(item: DownloadItem) = synchronized(lock) {
        downloads.postValue(downloads.value.orEmpty() + item)
    }

    /**
     * Mutate a single item in-place. [block] runs on a fresh copy;
     * the result replaces the old entry atomically.
     */
    fun update(id: Int, block: DownloadItem.() -> Unit) = synchronized(lock) {
        val list = downloads.value.orEmpty().map { item ->
            if (item.id == id) item.copy().also(block) else item
        }
        downloads.postValue(list)
    }

    fun remove(id: Int) = synchronized(lock) {
        downloads.postValue(downloads.value.orEmpty().filter { it.id != id })
    }

    fun clearFinished() = synchronized(lock) {
        downloads.postValue(downloads.value.orEmpty().filter {
            it.state == DownloadState.RUNNING || it.state == DownloadState.QUEUED
        })
    }

    fun getById(id: Int): DownloadItem? =
        downloads.value?.find { it.id == id }

    fun hasActive(): Boolean =
        downloads.value?.any {
            it.state == DownloadState.RUNNING || it.state == DownloadState.QUEUED
        } == true
}
