package space.karrarnazim.ConsoleFlow

import android.app.Activity
import android.view.KeyEvent
import android.webkit.WebView
import android.widget.EditText
import android.widget.FrameLayout

/**
 * Handles TV Remote (D-Pad) input and provides smart navigation support.
 * Supports D-Pad navigation, Back button, and Enter/OK button.
 */
class TVRemoteHandler(
    private val activity: Activity,
    private val webViewContainer: FrameLayout,
    private val topBar: android.widget.LinearLayout,
    private val bottomBar: android.widget.LinearLayout,
    private val textUrl: EditText,
    private val onNavigateBack: () -> Unit,
    private val onNavigateForward: () -> Unit,
    private val onReload: () -> Unit,
    private val onToggleMenu: () -> Unit
) {

    private var isFocusInWebView = true
    private var focusedElement: String? = null

    /**
     * Handles key events from the TV remote.
     * Returns true if the event was consumed, false otherwise.
     */
    fun handleKeyEvent(keyCode: Int, event: KeyEvent): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> handleDPadUp()
            KeyEvent.KEYCODE_DPAD_DOWN -> handleDPadDown()
            KeyEvent.KEYCODE_DPAD_LEFT -> handleDPadLeft()
            KeyEvent.KEYCODE_DPAD_RIGHT -> handleDPadRight()
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER -> handleDPadCenter()
            KeyEvent.KEYCODE_BACK -> handleBackButton()
            KeyEvent.KEYCODE_MENU -> handleMenuButton()
            KeyEvent.KEYCODE_TV_POWER,
            KeyEvent.KEYCODE_POWER -> false
            else -> false
        }
    }

    private fun handleDPadUp(): Boolean {
        val webView = webViewContainer.getChildAt(0) as? WebView ?: return false
        webView.evaluateJavascript(
            """
            (function() {
                var focused = document.activeElement;
                if (focused && focused !== document.body) {
                    var prev = focused.previousElementSibling;
                    if (prev) {
                        prev.focus();
                        prev.scrollIntoView({ behavior: 'smooth', block: 'center' });
                        return true;
                    }
                }
                window.scrollBy(0, -100);
                return true;
            })();
            """.trimIndent(),
            null
        )
        return true
    }

    private fun handleDPadDown(): Boolean {
        val webView = webViewContainer.getChildAt(0) as? WebView ?: return false
        webView.evaluateJavascript(
            """
            (function() {
                var focused = document.activeElement;
                if (focused && focused !== document.body) {
                    var next = focused.nextElementSibling;
                    if (next) {
                        next.focus();
                        next.scrollIntoView({ behavior: 'smooth', block: 'center' });
                        return true;
                    }
                }
                window.scrollBy(0, 100);
                return true;
            })();
            """.trimIndent(),
            null
        )
        return true
    }

    private fun handleDPadLeft(): Boolean {
        val webView = webViewContainer.getChildAt(0) as? WebView ?: return false
        webView.evaluateJavascript(
            """
            (function() {
                var focused = document.activeElement;
                if (focused && focused !== document.body) {
                    var focusableElements = Array.from(document.querySelectorAll(
                        'a, button, input, textarea, select, [tabindex]:not([tabindex="-1"])'
                    ));
                    var index = focusableElements.indexOf(focused);
                    if (index > 0) {
                        focusableElements[index - 1].focus();
                        focusableElements[index - 1].scrollIntoView({ behavior: 'smooth', block: 'center' });
                        return true;
                    }
                }
                window.scrollBy(-100, 0);
                return true;
            })();
            """.trimIndent(),
            null
        )
        return true
    }

    private fun handleDPadRight(): Boolean {
        val webView = webViewContainer.getChildAt(0) as? WebView ?: return false
        webView.evaluateJavascript(
            """
            (function() {
                var focused = document.activeElement;
                if (focused && focused !== document.body) {
                    var focusableElements = Array.from(document.querySelectorAll(
                        'a, button, input, textarea, select, [tabindex]:not([tabindex="-1"])'
                    ));
                    var index = focusableElements.indexOf(focused);
                    if (index >= 0 && index < focusableElements.length - 1) {
                        focusableElements[index + 1].focus();
                        focusableElements[index + 1].scrollIntoView({ behavior: 'smooth', block: 'center' });
                        return true;
                    }
                }
                window.scrollBy(100, 0);
                return true;
            })();
            """.trimIndent(),
            null
        )
        return true
    }

    private fun handleDPadCenter(): Boolean {
        val webView = webViewContainer.getChildAt(0) as? WebView ?: return false
        webView.evaluateJavascript(
            """
            (function() {
                var focused = document.activeElement;
                if (focused && focused !== document.body) {
                    if (typeof focused.click === 'function') {
                        focused.click();
                        return true;
                    }
                }
                return false;
            })();
            """.trimIndent(),
            null
        )
        return true
    }

    private fun handleBackButton(): Boolean {
        onNavigateBack()
        return true
    }

    private fun handleMenuButton(): Boolean {
        onToggleMenu()
        return true
    }

    /**
     * Injects CSS to highlight focused elements for TV viewing.
     */
    fun injectFocusHighlightCSS(webView: WebView) {
        webView.evaluateJavascript(
            """
            (function() {
                var style = document.createElement('style');
                style.textContent = `
                    *:focus {
                        outline: 3px solid #7DD3FC !important;
                        outline-offset: 3px !important;
                        box-shadow: 0 0 0 4px rgba(125, 211, 252, 0.28) !important,
                                    0 0 14px rgba(255, 255, 255, 0.22) !important;
                    }

                    a:focus, button:focus, input:focus, textarea:focus, select:focus,
                    [role="button"]:focus, [tabindex]:focus {
                        background-color: rgba(255, 255, 255, 0.12) !important;
                    }
                `;
                document.head.appendChild(style);
            })();
            """.trimIndent(),
            null
        )
    }

    /**
     * Enables spatial navigation for TV remotes.
     */
    fun enableSpatialNavigation(webView: WebView) {
        webView.isFocusable = true
        webView.isFocusableInTouchMode = true
        webView.requestFocus()
        injectFocusHighlightCSS(webView)
    }
}
