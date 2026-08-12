import java.io.File
import java.util.Properties
import kotlin.math.max
object GitVersion {
    private const val VERSION_FILE =
        "version.properties"
    /**
     * 返回:
     * [0] versionName
     * [1] versionCode
     */
    fun getVersion(): Array<String> {
        val savedVersion =
            readSavedVersion()
        val gitVersion =
            getGitCommitCount()
        val versionCode =
            if (gitVersion != null) {
                /*
                 * Git存在:
                 * 永远取最高值
                 */
                max(
                    gitVersion,
                    savedVersion
                )
            } else {
                /*
                 * 无Git:
                 * 基于历史版本递增
                 */
                savedVersion + 1
            }
        saveVersion(versionCode)
        return arrayOf(
            getVersionName(versionCode),
            versionCode.toString()
        )
    }
    /**
     * 获取Git提交次数
     */
    private fun getGitCommitCount(): Int? {
        return try {
            val process =
                ProcessBuilder(
                    "git",
                    "rev-list",
                    "--count",
                    "HEAD"
                )
                    .redirectErrorStream(true)
                    .start()
            process.inputStream
                .bufferedReader()
                .readText()
                .trim()
                .toInt()
        } catch (e: Exception) {
            null
        }
    }
    /**
     * 读取历史最高版本
     */
    private fun readSavedVersion(): Int {
        val file =
            File(VERSION_FILE)
        if (!file.exists()) {
            return 0
        }
        return try {
            val properties =
                Properties()
            file.inputStream()
                .use {
                    properties.load(it)
                }
            properties
                .getProperty(
                    "VERSION_CODE",
                    "0"
                )
                .toInt()
        } catch (e: Exception) {
            0
        }
    }
    /**
     * 保存最高版本
     */
    private fun saveVersion(
        version: Int
    ) {
        val file =
            File(VERSION_FILE)
        val properties =
            Properties()
        properties.setProperty(
            "VERSION_CODE",
            version.toString()
        )
        file.outputStream()
            .use {
                properties.store(
                    it,
                    "Auto generated version"
                )
            }
    }
    /**
     * VersionCode转换VersionName
     *
     * 0   -> 1.0.0
     * 1   -> 1.0.1
     * 9   -> 1.0.9
     * 10  -> 1.1.0
     * 99  -> 1.9.9
     * 100 -> 2.0.0
     */
    private fun getVersionName(
        versionCode: Int
    ): String {
        val patch =
            versionCode % 10
        val minor =
            (versionCode / 10) % 10
        val major =
            versionCode / 100 + 1
        return "$major.$minor.$patch"
    }
}