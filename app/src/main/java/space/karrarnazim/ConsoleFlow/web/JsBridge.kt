package space.karrarnazim.ConsoleFlow

class JsBridge(
    private val navigate: (String) -> Unit,
    private val setSwipeRefresh: (Boolean) -> Unit
) {
    @android.webkit.JavascriptInterface
    fun navigate(input: String) = navigate(input)

    @android.webkit.JavascriptInterface
    fun setSwipeRefresh(enabled: Boolean) = setSwipeRefresh(enabled)
}
