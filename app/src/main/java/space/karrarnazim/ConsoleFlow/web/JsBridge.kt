package space.karrarnazim.ConsoleFlow

class JsBridge(
    private val navigateCallback: (String) -> Unit,
    private val setSwipeRefreshCallback: (Boolean) -> Unit
) {
    @android.webkit.JavascriptInterface
    fun navigate(input: String) {
        navigateCallback(input)
    }

    @android.webkit.JavascriptInterface
    fun setSwipeRefresh(enabled: Boolean) {
        setSwipeRefreshCallback(enabled)
    }
}
