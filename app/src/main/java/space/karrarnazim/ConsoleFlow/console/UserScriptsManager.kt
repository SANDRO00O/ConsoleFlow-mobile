package space.karrarnazim.ConsoleFlow

object UserScriptsManager {
    fun buildInjectedScripts(consoleEnabled: Boolean, customJs: String): String {
        val erudaTags = if (consoleEnabled) {
            "<script src=\"https://eruda.local/eruda.js\"></script>" +
                "<script>(function(){if(window.__erudaInited){" +
                "try{eruda.show();window.__cfConsoleEnabled=true;}catch(e){};return;}" +
                "try{eruda.init();window.__erudaInited=true;window.__cfConsoleEnabled=true;" +
                "}catch(e){}})()</script>"
        } else ""

        val customJsTag = customJs.takeIf { it.isNotEmpty() }
            ?.let {
                // BUG-S FIX: escape any literal "</script" sequence so the HTML
                // parser can't mistake it for the tag's real closing sequence.
                // Safe everywhere it can legitimately occur in JS — inside a
                // string ("\/" is just "/"), inside a comment (inert text),
                // or inside a regex literal (the standard way to escape "/").
                val escaped = it.replace("</script", "<\\/script", ignoreCase = true)
                "<script>$escaped</script>"
            } ?: ""

        return erudaTags + customJsTag
    }
}
