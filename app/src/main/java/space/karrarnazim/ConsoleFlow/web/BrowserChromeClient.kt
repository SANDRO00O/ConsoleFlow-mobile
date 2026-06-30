package space.karrarnazim.ConsoleFlow

import android.graphics.Bitmap
import android.view.View
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebView

class BrowserChromeClient(
    private val tabId: Int,
    private val onProgressChangedUi: (Int, Int) -> Unit,
    private val onReceivedIconUi: (Int, Bitmap) -> Unit,
    private val onShowCustomViewUi: (View?, WebChromeClient.CustomViewCallback?) -> Unit,
    private val onHideCustomViewUi: () -> Unit,
    private val onPermissionRequestUi: (PermissionRequest?) -> Unit
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
}
