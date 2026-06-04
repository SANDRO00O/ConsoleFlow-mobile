package space.karrarnazim.ConsoleFlow

data class TabGroup(
    val id: Int,
    var name: String,
    val tabs: MutableList<TabState> = mutableListOf()
) : Serializable
