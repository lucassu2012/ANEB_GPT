// ANEB Probe — :probe 模块（阶段 0：跑通一次 S1 并把全部时间戳打到屏幕日志）
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

val releaseStorePath = providers.gradleProperty("aneb.release.storeFile").orNull
    ?: System.getenv("ANEB_RELEASE_STORE_FILE")
val releaseStorePassword = providers.gradleProperty("aneb.release.storePassword").orNull
    ?: System.getenv("ANEB_RELEASE_STORE_PASSWORD")
val releaseKeyAlias = providers.gradleProperty("aneb.release.keyAlias").orNull
    ?: System.getenv("ANEB_RELEASE_KEY_ALIAS")
val releaseKeyPassword = providers.gradleProperty("aneb.release.keyPassword").orNull
    ?: System.getenv("ANEB_RELEASE_KEY_PASSWORD")
val releaseSigningReady = listOf(
    releaseStorePath,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
).all { !it.isNullOrBlank() }

android {
    namespace = "com.aneb.probe"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.aneb.probe"
        minSdk = 29 // CellInfoNr / 5G API 需要（设计文档 §5）
        targetSdk = 35
        versionCode = 38
        versionName = "0.5.6"
    }

    signingConfigs {
        if (releaseSigningReady) {
            create("release") {
                storeFile = file(releaseStorePath!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = true
            }
        }
    }

    buildTypes {
        release {
            // 首次公开发布先保留可审计堆栈；R8 在建立 release 回归基线后单独启用。
            isMinifyEnabled = false
            if (releaseSigningReady) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            // Codex 与 Claude 并行验收：debug 独立安装，release 仍保留正式包名 com.aneb.probe。
            applicationIdSuffix = ".codex"
            versionNameSuffix = "-codex"
            // 明文流量仅经 src/debug/res/xml/network_security_config.xml 允许（仿真服务器联调）
            // release 变体不带该配置，targetSdk>=28 默认禁明文
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        // BuildConfig.DEBUG 门控注入透传（P1 范围 9：--es inject 仅 debug 生效）
        buildConfig = true
    }

    sourceSets {
        getByName("main") {
            // 打包内置 profiles 副本（P1 范围 1：/api/v1/profiles 拉取失败时的兜底）。
            // 直接指向仓库共享目录，单一事实来源，防内置副本与服务端版本静默漂移。
            assets.srcDirs("../../profiles")
        }
    }
}

ksp {
    // Room schema 纳入版本库；迁移评审不再只依赖手写 SQL 与运行时发现。
    arg("room.schemaLocation", file("$projectDir/schemas").path)
    arg("room.incremental", "true")
}

val verifyReleaseSigning by tasks.registering {
    group = "verification"
    description = "Fail closed when release signing ownership has not been configured."
    doLast {
        check(releaseSigningReady) {
            "Release signing is not configured. Set ANEB_RELEASE_STORE_FILE, " +
                "ANEB_RELEASE_STORE_PASSWORD, ANEB_RELEASE_KEY_ALIAS and ANEB_RELEASE_KEY_PASSWORD."
        }
        check(file(releaseStorePath!!).isFile) { "Release keystore does not exist: $releaseStorePath" }
    }
}

tasks.matching { it.name == "assembleRelease" || it.name == "bundleRelease" || it.name == "installRelease" }
    .configureEach { dependsOn(verifyReleaseSigning) }

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)

    // 阶段 2 P2-C05：Cronet 内嵌网络栈（TCP(TLS) vs QUIC(h3) 背靠背 A/B，D-17/D-19）。
    // 仅 AbRunner/CronetStreamClient 使用——OkHttp 主测量路径不变；两栈计时钩子
    // 粒度不同，数据不可互比（A/B 结论只在 Cronet 栈内得出）。
    implementation(libs.cronet.embedded)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    testImplementation(libs.junit)
}
