package space.karrarnazim.ConsoleFlow

import androidx.lifecycle.MutableLiveData
import java.util.concurrent.atomic.AtomicInteger

object DownloadTracker {

    private val _list = mutableListOf<DownloadItem>()
    val downloads: MutableLiveData<List<DownloadItem>> = MutableLiveData(emptyList())

    private val idGen = AtomicInteger(1)
    private val lock  = Any()

    fun nextId(): Int = idGen.getAndIncrement()

    fun add(item: DownloadItem) = synchronized(lock) {
        _list.add(item)
        downloads.postValue(_list.toList())
    }

    fun update(id: Int, block: DownloadItem.() -> Unit) = synchronized(lock) {
        val idx = _list.indexOfFirst { it.id == id }
        if (idx >= 0) {
            _list[idx] = _list[idx].copy().also(block)
            downloads.postValue(_list.toList())
        }
    }

    fun remove(id: Int) = synchronized(lock) {
        _list.removeAll { it.id == id }
        downloads.postValue(_list.toList())
    }

    fun clearFinished() = synchronized(lock) {
        _list.removeAll {
            it.state != DownloadState.RUNNING && it.state != DownloadState.QUEUED
        }
        downloads.postValue(_list.toList())
    }

    fun getById(id: Int): DownloadItem? = synchronized(lock) {
        _list.find { it.id == id }
    }

    fun hasActive(): Boolean = synchronized(lock) {
        _list.any { it.state == DownloadState.RUNNING || it.state == DownloadState.QUEUED }
    }
}
