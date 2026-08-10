@file:JvmName("HostInfo")
package moe.ono

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.pm.PackageInfoCompat
import moe.ono.util.Logger

const val PACKAGE_NAME_QQ = "com.tencent.mobileqq"
const val PACKAGE_NAME_QQ_INTERNATIONAL = "com.tencent.mobileqqi"
const val PACKAGE_NAME_QQ_LITE = "com.tencent.qqlite"
const val PACKAGE_NAME_QQ_HD = "com.tencent.minihd.qq"
const val PACKAGE_NAME_TIM = "com.tencent.tim"
const val PACKAGE_NAME_SELF = BuildConfig.APPLICATION_ID

lateinit var hostInfo: HostInfoImpl

fun init(applicationContext: Application) {
    if (::hostInfo.isInitialized) throw IllegalStateException("Host Information Provider has been already initialized")
    val packageInfo = getHostInfo(applicationContext)
    val packageName = applicationContext.packageName
    hostInfo = HostInfoImpl(
        applicationContext,
        packageName,
        applicationContext.applicationInfo.loadLabel(applicationContext.packageManager).toString(),
        PackageInfoCompat.getLongVersionCode(packageInfo),
        PackageInfoCompat.getLongVersionCode(packageInfo).toInt(),
        packageInfo.versionName.toString(),
        when (packageName) {
            PACKAGE_NAME_QQ -> {
                val appSettingParams = packageInfo.applicationInfo?.metaData
                    ?.getString("AppSetting_params")
                    .orEmpty()
                if ("GoogleMarket" in appSettingParams) {
                    HostSpecies.QQ_Play
                } else HostSpecies.QQ
            }
            PACKAGE_NAME_TIM -> HostSpecies.TIM
            PACKAGE_NAME_QQ_LITE -> HostSpecies.QQ_Lite
            PACKAGE_NAME_QQ_INTERNATIONAL -> HostSpecies.QQ_International
            PACKAGE_NAME_QQ_HD -> HostSpecies.QQ_HD
            PACKAGE_NAME_SELF -> HostSpecies.ono
            else -> HostSpecies.Unknown
        },
    )
}

private fun getHostInfo(context: Context): PackageInfo {
    try {
        return context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_META_DATA)
    } catch (e: PackageManager.NameNotFoundException) {
        Log.e("Utils", "Can not get PackageInfo!", e)
        throw e
    }
}

fun isTim(): Boolean {
    return hostInfo.hostSpecies == HostSpecies.TIM
}

fun isPlayQQ(): Boolean {
    return hostInfo.hostSpecies == HostSpecies.QQ_Play
}

fun requireMinQQVersion(versionCode: Long): Boolean {
    return requireMinVersion(versionCode, HostSpecies.QQ)
}

fun requireMinPlayQQVersion(versionCode: Long): Boolean {
    return requireMinVersion(versionCode, HostSpecies.QQ_Play)
}

fun requireMinTimVersion(versionCode: Long): Boolean {
    return requireMinVersion(versionCode, HostSpecies.TIM)
}

fun requireMinVersion(versionCode: Long, hostSpecies: HostSpecies): Boolean {
    return hostInfo.hostSpecies == hostSpecies && hostInfo.versionCode >= versionCode
}

fun requireMinVersion(
    QQVersionCode: Long = Long.MAX_VALUE,
    TimVersionCode: Long = Long.MAX_VALUE,
    PlayQQVersionCode: Long = Long.MAX_VALUE
): Boolean {
    return requireMinQQVersion(QQVersionCode) || requireMinTimVersion(TimVersionCode) || requireMinPlayQQVersion(PlayQQVersionCode)
}

val isInModuleProcess: Boolean
    get() = hostInfo.hostSpecies == HostSpecies.ono

val isInHostProcess: Boolean get() = !isInModuleProcess

val isAndroidxFileProviderAvailable: Boolean by lazy {
    val ctx = hostInfo.application
    // check if androidx.core.content.FileProvider is available
    val pm = ctx.packageManager
    try {
        pm.getProviderInfo(ComponentName(hostInfo.packageName, "androidx.core.content.FileProvider"), 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }
}

data class HostInfoImpl(
    val application: Application,
    val packageName: String,
    val hostName: String,
    val versionCode: Long,
    val versionCode32: Int,
    val versionName: String,
    val hostSpecies: HostSpecies
)

enum class HostSpecies {
    QQ,
    TIM,
    QQ_Play,
    QQ_Lite,
    QQ_International,
    QQ_HD,
    ono,
    Unknown
}

fun overrideVersionCodeForLSPatchModified1(newVersionCode: Int) {
    Logger.w("Overriding version code from ${hostInfo.versionCode32} to $newVersionCode")
    hostInfo = HostInfoImpl(
        hostInfo.application,
        hostInfo.packageName,
        hostInfo.hostName,
        newVersionCode.toLong(),
        newVersionCode,
        hostInfo.versionName,
        hostInfo.hostSpecies
    )
}
