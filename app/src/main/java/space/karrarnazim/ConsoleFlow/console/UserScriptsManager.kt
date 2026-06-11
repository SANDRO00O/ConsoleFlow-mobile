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
            ?.let { "<script>$it</script>" } ?: ""

        return erudaTags + customJsTag
    }
}
