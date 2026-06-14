package space.karrarnazim.ConsoleFlow

import java.io.Serializable


data class TabGroup(
    val id: Int,
    var name: String,
    val tabs: MutableList<TabState> = mutableListOf()
) : Serializable
