package space.karrarnazim.ConsoleFlow

object ConsoleScripts {
    fun initScript(): String =
        "(function(){" +
        "window.__cfConsoleEnabled=true;" +
        "var el=document.getElementById('eruda');" +
        "if(window.__erudaInited){" +
            "try{if(window.eruda&&eruda.show)eruda.show();}catch(e){}" +
            "if(el)el.style.display='';" +
            "return;" +
        "}" +
        "if(typeof eruda!=='undefined'){" +
            "try{eruda.init();window.__erudaInited=true;window.__cfConsoleEnabled=true;" +
            "if(el)el.style.display='';}catch(e){}" +
            "return;" +
        "}" +
        "var x=new XMLHttpRequest();" +
        "x.open('GET','https://eruda.local/eruda.js',true);" +
        "x.onload=function(){" +
            "if(window.__erudaInited)return;" +
            "try{eval(x.responseText);eruda.init();window.__erudaInited=true;" +
            "window.__cfConsoleEnabled=true;if(el)el.style.display='';}catch(e){}" +
        "};" +
        "x.send();" +
        "})()"

    fun disableScript(): String =
        "(function(){" +
        "window.__cfConsoleEnabled=false;" +
        "try{if(window.eruda&&eruda.hide)eruda.hide();}catch(e){}" +
        "var el=document.getElementById('eruda');" +
        "if(el){el.style.display='none';el.classList.add('__cf_console_hidden');}" +
        "})()"

    fun touchHookScript(): String =
        "(function(){" +
        "if(window.__erudaTouchHooked)return;" +
        "window.__erudaTouchHooked=true;" +
        "function getErudaEl(){return document.getElementById('eruda');}" +
        "function consoleEnabled(){return window.__cfConsoleEnabled!==false;}" +
        "document.addEventListener('touchstart',function(e){" +
            "if(!consoleEnabled())return;" +
            "var el=getErudaEl();" +
            "if(el&&el.contains(e.target)){try{Android.setSwipeRefresh(false);}catch(ex){}}" +
        "},{capture:true,passive:true});" +
        "document.addEventListener('touchend',function(){" +
            "if(!consoleEnabled())return;" +
            "try{Android.setSwipeRefresh(true);}catch(ex){}" +
        "},{capture:true,passive:true});" +
        "})()"
}
