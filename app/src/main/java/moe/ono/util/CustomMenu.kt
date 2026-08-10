package moe.ono.util

import com.github.kyuubiran.ezxhelper.utils.findAllMethods
import com.github.kyuubiran.ezxhelper.utils.findMethod
import moe.ono.constants.Constants
import moe.ono.dexkit.TargetManager.requireClazz
import moe.ono.reflex.Reflex
import moe.ono.util.HostInfo.requireMinQQVersion
import moe.ono.util.HostInfo.requireMinTimVersion
import net.bytebuddy.ByteBuddy
import net.bytebuddy.android.AndroidClassLoadingStrategy
import net.bytebuddy.android.InjectableDexClassLoader
import net.bytebuddy.implementation.FixedValue
import net.bytebuddy.implementation.MethodCall
import net.bytebuddy.matcher.ElementMatchers
import java.lang.reflect.Field

object CustomMenu {
    @Throws(ReflectiveOperationException::class)
    fun createItem(clazz: Class<*>, id: Int, title: String?, icon: Int): Any {
        return try {
            try {
                val initWithArgv = clazz.getConstructor(Int::class.javaPrimitiveType, String::class.java, Int::class.javaPrimitiveType)
                initWithArgv.newInstance(id, title, icon)
            } catch (unused: NoSuchMethodException) {
                //no direct constructor, reflex
                val item = createItem(clazz, id, title)
                var f: Field? = Reflex.findFieldOrNull(clazz, Int::class.javaPrimitiveType, "b")
                if (f == null) {
                    f = Reflex.findField(clazz, Int::class.javaPrimitiveType, "icon")
                }
                f!!.isAccessible = true
                f[item] = icon
                item
            }
        } catch (e: ReflectiveOperationException) {
            Logger.w(e)
            //sign... drop icon
            createItem(clazz, id, title)
        }
    }

    @JvmStatic
    @Throws(ReflectiveOperationException::class)
    fun createItem(clazz: Class<*>, id: Int, title: String?): Any {
        try {
            val initWithArgv = clazz.getConstructor(Int::class.javaPrimitiveType, String::class.java)
            return initWithArgv.newInstance(id, title)
        } catch (ignored: NoSuchMethodException) {
        } catch (e: IllegalAccessException) {
            throw AssertionError(e)
        }
        val item: Any = clazz.getDeclaredConstructor().newInstance()
        var f: Field? = Reflex.findFieldOrNull(clazz, Int::class.javaPrimitiveType, "id")
        if (f == null) {
            f = Reflex.findField(clazz, Int::class.javaPrimitiveType, "a")
        }
        f!!.isAccessible = true
        f[item] = id
        f = Reflex.findFieldOrNull(clazz, String::class.java, "title")
        if (f == null) {
            f = Reflex.findField(clazz, String::class.java, "a")
        }
        f!!.isAccessible = true
        f[item] = title
        return item
    }


    private val strategy by lazy {
        AndroidClassLoadingStrategy.Injecting()
    }

    private lateinit var injectionClassLoader: ClassLoader

    private fun getOrCreateInjectionClassLoader(parent: ClassLoader): ClassLoader {
        if (!::injectionClassLoader.isInitialized) {
            injectionClassLoader = InjectableDexClassLoader(parent)
        }
        return injectionClassLoader
    }

    /**
     * Remember to add DexKitTarget AbstractQQCustomMenuItem to the constructor!
     */
    @JvmStatic
    fun createItemNt(msg: Any, text: String, id: Int, click: () -> Unit): Any {
        val msgClass = Initiator.loadClass("com.tencent.mobileqq.aio.msg.AIOMsgItem")
        val absMenuItem = requireClazz(Constants.ClazzCacheKey_AbstractQQCustomMenuItem)
        val clickName = absMenuItem.findMethod {
            returnType == Void.TYPE && parameterTypes.isEmpty()
        }.name
        val menuItemClass = ByteBuddy()
            .subclass(absMenuItem)
            .method(ElementMatchers.returns(String::class.java))
            .intercept(FixedValue.value(text))
            .method(ElementMatchers.returns(Int::class.java))
            .intercept(FixedValue.value(id))
            .method(ElementMatchers.named(clickName))
            .intercept(MethodCall.call { click() })
            .make()
            .load(getOrCreateInjectionClassLoader(absMenuItem.classLoader!!), strategy)
            .loaded
        return menuItemClass.getDeclaredConstructor(msgClass)
            .newInstance(msg)
    }

    /**
     * Starting from QQ version 9.0.0, support for menu icons was added.
     */
    @JvmStatic
    fun createItemIconNt(msg: Any, text: String, icon: Int, id: Int, click: () -> Unit): Any {
        if (!requireMinQQVersion(QQVersion.QQ_9_0_0) && !requireMinTimVersion(TIMVersion.TIM_4_0_95)) return createItemNt(msg, text, id, click)
        val msgClass = Initiator.loadClass("com.tencent.mobileqq.aio.msg.AIOMsgItem")
        val absMenuItem = requireClazz(Constants.ClazzCacheKey_AbstractQQCustomMenuItem)
        val (iconName, idName) = absMenuItem.findAllMethods { returnType == Int::class.java && parameterTypes.isEmpty() }.map { it.name }
        val clickName = absMenuItem.findMethod { returnType == Void.TYPE && parameterTypes.isEmpty() }.name
        val menuItemClass = ByteBuddy()
            .subclass(absMenuItem)
            .method(ElementMatchers.returns(String::class.java))
            .intercept(FixedValue.value(text))
            .method(ElementMatchers.named(iconName))
            .intercept(FixedValue.value(icon))
            .method(ElementMatchers.named(idName))
            .intercept(FixedValue.value(id))
            .method(ElementMatchers.named(clickName))
            .intercept(MethodCall.call { click() })
            .make()
            .load(getOrCreateInjectionClassLoader(absMenuItem.classLoader!!), strategy)
            .loaded
        return menuItemClass.getDeclaredConstructor(msgClass).newInstance(msg)
    }

    fun checkArrayElementNonNull(array: Array<Any?>?) {
        if (array == null) {
            throw NullPointerException("array is null")
        }
        for (i in array.indices) {
            if (array[i] == null) {
                throw NullPointerException("array[" + i + "] is null, length=" + array.size)
            }
        }
    }
}
