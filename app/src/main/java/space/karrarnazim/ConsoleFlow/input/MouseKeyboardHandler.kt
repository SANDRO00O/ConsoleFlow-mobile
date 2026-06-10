package space.karrarnazim.ConsoleFlow

import android.app.Activity
import android.view.KeyEvent
import android.webkit.WebView
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout

/**
 * Handles Mouse and Keyboard input for ConsoleFlow.
 * Supports keyboard shortcuts, mouse clicks, and right-click context menus.
 */
class MouseKeyboardHandler(
    private val activity: Activity,
    private val webViewContainer: FrameLayout,
    private val topBar: LinearLayout,
    private val textUrl: EditText,
    private val onNewTab: () -> Unit,
    private val onCloseTab: () -> Unit,
    private val onReload: () -> Unit,
    private val onFind: () -> Unit,
    private val onFocusUrlBar: () -> Unit,
    private val onToggleMenu: () -> Unit,
    private val onNavigateBack: () -> Unit,
    private val onNavigateForward: () -> Unit,
    private val onToggleFullscreen: () -> Unit,
    private val onToggleDarkMode: () -> Unit
) {

    /**
     * Handles keyboard shortcuts.
     * Returns true if the shortcut was consumed, false otherwise.
     */
    fun handleKeyboardShortcut(keyCode: Int, event: KeyEvent): Boolean {
        val isCtrlPressed = event.isCtrlPressed
        val isShiftPressed = event.isShiftPressed
        val isAltPressed = event.isAltPressed

        return when {
            // Ctrl + T: New Tab
            isCtrlPressed && keyCode == KeyEvent.KEYCODE_T -> {
                onNewTab()
                true
            }
            // Ctrl + W: Close Tab
            isCtrlPressed && keyCode == KeyEvent.KEYCODE_W -> {
                onCloseTab()
                true
            }
            // Ctrl + R or F5: Reload
            (isCtrlPressed && keyCode == KeyEvent.KEYCODE_R) ||
            keyCode == KeyEvent.KEYCODE_F5 -> {
                onReload()
                true
            }
            // Ctrl + F or F3: Find in Page
            (isCtrlPressed && keyCode == KeyEvent.KEYCODE_F) ||
            keyCode == KeyEvent.KEYCODE_F3 -> {
                onFind()
                true
            }
            // Ctrl + L: Focus URL Bar
            isCtrlPressed && keyCode == KeyEvent.KEYCODE_L -> {
                onFocusUrlBar()
                true
            }
            // Alt + Left: Navigate Back
            isAltPressed && keyCode == KeyEvent.KEYCODE_DPAD_LEFT -> {
                onNavigateBack()
                true
            }
            // Alt + Right: Navigate Forward
            isAltPressed && keyCode == KeyEvent.KEYCODE_DPAD_RIGHT -> {
                onNavigateForward()
                true
            }
            // F11 or Ctrl + Shift + F: Toggle Fullscreen
            keyCode == KeyEvent.KEYCODE_F11 ||
            (isCtrlPressed && isShiftPressed && keyCode == KeyEvent.KEYCODE_F) -> {
                onToggleFullscreen()
                true
            }
            // Ctrl + Shift + D: Toggle Dark Mode
            isCtrlPressed && isShiftPressed && keyCode == KeyEvent.KEYCODE_D -> {
                onToggleDarkMode()
                true
            }
            // Alt + M: Toggle Menu
            isAltPressed && keyCode == KeyEvent.KEYCODE_M -> {
                onToggleMenu()
                true
            }
            // Tab: Focus Navigation
            keyCode == KeyEvent.KEYCODE_TAB -> {
                handleTabNavigation(isShiftPressed)
                true
            }
            else -> false
        }
    }

    /**
     * Handles Tab key for focus navigation.
     */
    private fun handleTabNavigation(isShiftPressed: Boolean) {
        val webView = webViewContainer.getChildAt(0) as? WebView ?: return

        webView.evaluateJavascript(
            """
            (function() {
                var focusableElements = Array.from(document.querySelectorAll(
                    'a, button, input, textarea, select, [tabindex]:not([tabindex="-1"])'
                ));
                
                if (focusableElements.length === 0) return;
                
                var focused = document.activeElement;
                var index = focusableElements.indexOf(focused);
                
                if ($isShiftPressed) {
                    // Shift + Tab: Focus previous element
                    index = index <= 0 ? focusableElements.length - 1 : index - 1;
                } else {
                    // Tab: Focus next element
                    index = index >= focusableElements.length - 1 ? 0 : index + 1;
                }
                
                focusableElements[index].focus();
                focusableElements[index].scrollIntoView({ behavior: 'smooth', block: 'center' });
            })();
            """.trimIndent(),
            null
        )
    }

    /**
     * Injects CSS to improve keyboard accessibility and focus visibility.
     */
    fun injectKeyboardAccessibilityCSS(webView: WebView) {
        webView.evaluateJavascript(
            """
            (function() {
                var style = document.createElement('style');
                style.textContent = `
                    /* Improve focus visibility for keyboard navigation */
                    *:focus {
                        outline: 2px solid #4A90E2 !important;
                        outline-offset: 2px !important;
                    }
                    
                    /* Ensure buttons are keyboard accessible */
                    button:focus, a:focus, input:focus, textarea:focus, select:focus {
                        box-shadow: 0 0 0 3px rgba(74, 144, 226, 0.3) !important;
                    }
                    
                    /* Improve link visibility */
                    a {
                        text-decoration: underline;
                    }
                    
                    /* Better button focus state */
                    button:focus {
                        background-color: rgba(74, 144, 226, 0.1) !important;
                    }
                `;
                document.head.appendChild(style);
            })();
            """.trimIndent(),
            null
        )
    }

    /**
     * Injects JavaScript to handle right-click context menu events.
     */
    fun injectContextMenuSupport(webView: WebView) {
        webView.evaluateJavascript(
            """
            (function() {
                document.addEventListener('contextmenu', function(e) {
                    // Let the native context menu handler take over
                    // This is handled by WebView's setOnCreateContextMenuListener
                });
            })();
            """.trimIndent(),
            null
        )
    }

    /**
     * Handles special keys like Home, End, Page Up, Page Down.
     */
    fun handleSpecialKeys(keyCode: Int): Boolean {
        val webView = webViewContainer.getChildAt(0) as? WebView ?: return false

        return when (keyCode) {
            KeyEvent.KEYCODE_HOME -> {
                webView.evaluateJavascript(
                    """
                    (function() {
                        window.scrollTo(0, 0);
                    })();
                    """.trimIndent(),
                    null
                )
                true
            }
            KeyEvent.KEYCODE_MOVE_END -> {
                webView.evaluateJavascript(
                    """
                    (function() {
                        window.scrollTo(0, document.body.scrollHeight);
                    })();
                    """.trimIndent(),
                    null
                )
                true
            }
            KeyEvent.KEYCODE_PAGE_UP -> {
                webView.evaluateJavascript(
                    """
                    (function() {
                        window.scrollBy(0, -window.innerHeight);
                    })();
                    """.trimIndent(),
                    null
                )
                true
            }
            KeyEvent.KEYCODE_PAGE_DOWN -> {
                webView.evaluateJavascript(
                    """
                    (function() {
                        window.scrollBy(0, window.innerHeight);
                    })();
                    """.trimIndent(),
                    null
                )
                true
            }
            KeyEvent.KEYCODE_ESCAPE -> {
                webView.evaluateJavascript(
                    """
                    (function() {
                        // Trigger escape event for modals or fullscreen elements
                        var event = new KeyboardEvent('keydown', { key: 'Escape', code: 'Escape' });
                        document.dispatchEvent(event);
                    })();
                    """.trimIndent(),
                    null
                )
                true
            }
            else -> false
        }
    }

    /**
     * Enables smart keyboard input handling for forms and text fields.
     */
    fun enableSmartKeyboardHandling(webView: WebView) {
        webView.evaluateJavascript(
            """
            (function() {
                // Improve form input handling
                document.addEventListener('keydown', function(e) {
                    // Allow Enter to submit forms
                    if (e.key === 'Enter' && e.target.tagName === 'TEXTAREA') {
                        if (e.ctrlKey) {
                            e.target.form?.submit();
                        }
                    }
                });
                
                // Improve text selection
                document.addEventListener('keydown', function(e) {
                    if (e.ctrlKey && e.key === 'a') {
                        document.execCommand('selectAll');
                    }
                });
            })();
            """.trimIndent(),
            null
        )
    }
}
