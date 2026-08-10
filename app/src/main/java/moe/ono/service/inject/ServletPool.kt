package moe.ono.service.inject

import moe.ono.service.inject.servlets.IServlet
import moe.ono.reflex.fieldValueAs
import moe.ono.reflex.invoke
import moe.ono.util.Logger
import moe.ono.util.QAppUtils
import mqq.app.Servlet
import mqq.app.ServletContainer
import java.util.concurrent.ConcurrentHashMap

object ServletPool {

    private val servletArray = arrayOf<Class<out Servlet>>(
        IServlet::class.java,
    )

    private val servletMap = ConcurrentHashMap<Class<out Servlet>, Servlet>()

    fun injectServlet() {
        val servletContainer = QAppUtils.getAppRuntime().invoke("getServletContainer") as ServletContainer
        val managedServlet = servletContainer
            .fieldValueAs<ConcurrentHashMap<String, Servlet>>("managedServlet")!!
        for (servletClass in servletArray) {
            val servlet = servletClass.getDeclaredConstructor().newInstance()
            servlet.invoke("init", QAppUtils.getAppRuntime(), servletContainer)
            servlet.invoke("onCreate")
            managedServlet[servlet::class.java.name] = servlet
            servletMap[servlet::class.java] = servlet
            Logger.d("inject servlet: $servletClass")
        }
    }

    @Synchronized
    @Suppress("UNCHECKED_CAST")
    fun <T : Servlet> getServlet(servletClass: Class<T>): T {
        return servletMap[servletClass] as T
    }

    val iServlet: IServlet by lazy { getServlet(IServlet::class.java) }
}

