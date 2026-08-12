import java.io.ByteArrayOutputStream

plugins {
    alias(libs.plugins.agp.app)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.github.fairyxh.VirtualEnv"
    compileSdk = 37
    buildToolsVersion = "37.0.0"
    val gitVersion = GitVersion.getVersion()
    defaultConfig {
        applicationId = "io.github.fairyxh.VirtualEnv"
        minSdk = 26
        // targetSdk 32：ColorOS 的 WallpaperManager 读取壁纸位图检查
        // READ_EXTERNAL_STORAGE，targetSdk >= 33 时该权限不可授予（死路）
        targetSdk = 33
        versionName = gitVersion[0]
        versionCode = gitVersion[1].toInt()
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles("proguard-rules.pro")
            signingConfig = signingConfigs["debug"]
        }
    }

    buildFeatures {
        viewBinding = true
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    packaging {
        resources {
            merges += "META-INF/xposed/*"
            excludes += "META-INF/versions/**"
            excludes += "META-INF/INDEX.LIST"
            excludes += "META-INF/DEPENDENCIES"
            excludes += "META-INF/LICENSE*"
            excludes += "META-INF/NOTICE*"
        }
        jniLibs {
            useLegacyPackaging = true
        }
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = false
    }
}

private fun getGitCommitCount(): Int {
    return try {
        val process = ProcessBuilder(
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
        1
    }
}
fun getGitVersion(): Array<String> {
    val count = getGitCommitCount()
    return arrayOf(
        "1.0.$count",
        count.toString()
    )
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21
    }
}

dependencies {
    implementation(libs.androidx.ui.graphics)
    compileOnly(libs.libxposed.api)
    implementation(libs.libxposed.service)

    // AndroidX Fragment（控制端 UI 导航）
    implementation(libs.androidx.fragment)

    // 高德地图 SDK（本地 jar，含 3DMap/Search/Location）
    implementation(files("libs/AMap3DMap.jar"))

    // Compose + Liquid Glass（backdrop）
    implementation(libs.androidx.activity.compose)
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui.graphics)
    implementation(libs.kyant.backdrop)
    implementation(libs.kyant.shapes)
}
