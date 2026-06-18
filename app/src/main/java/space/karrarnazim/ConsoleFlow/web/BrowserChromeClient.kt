package space.karrarnazim.ConsoleFlow

import android.graphics.Bitmap
import android.net.Uri
import android.view.View
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView

class BrowserChromeClient(
    private val tabId: Int,
    private val onProgressChangedUi: (Int, Int) -> Unit,
    private val onReceivedIconUi: (Int, Bitmap) -> Unit,
    private val onShowCustomViewUi: (View?, WebChromeClient.CustomViewCallback?) -> Unit,
    private val onHideCustomViewUi: () -> Unit,
    private val onPermissionRequestUi: (PermissionRequest?) -> Unit,
    // BUG-N FIX: without this, <input type="file"> on any page silently did
    // nothing — WebView's default onShowFileChooser returns false, so the
    // system never even opens a picker. Returns true to tell WebView we
    // handled it (the picker itself is launched by the Activity).
    private val onShowFileChooserUi: (ValueCallback<Array<Uri>>?, WebChromeClient.FileChooserParams?) -> Boolean
) : WebChromeClient() {
    override fun onProgressChanged(view: WebView, newProgress: Int) {
        onProgressChangedUi(tabId, newProgress)
    }

    override fun onReceivedIcon(view: WebView?, icon: Bitmap?) {
        super.onReceivedIcon(view, icon)
        icon ?: return
        onReceivedIconUi(tabId, icon)
    }

    override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
        onShowCustomViewUi(view, callback)
    }

    override fun onHideCustomView() {
        onHideCustomViewUi()
    }

    override fun onPermissionRequest(request: PermissionRequest?) {
        onPermissionRequestUi(request)
    }

    override fun onShowFileChooser(
        webView: WebView?,
        filePathCallback: ValueCallback<Array<Uri>>?,
        fileChooserParams: FileChooserParams?
    ): Boolean = onShowFileChooserUi(filePathCallback, fileChooserParams)
}
