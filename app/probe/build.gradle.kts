// ANEB Probe — :probe 模块（阶段 0：跑通一次 S1 并把全部时间戳打到屏幕日志）
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest

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

// Activation requires a separate, Product-Owner-reviewed source change containing the public
// certificate fingerprint. Never accept an expected fingerprint beside the keystore itself.
val approvedLocalReleaseSigningCertificateSha256: Set<String> = emptySet()
val releaseCertificateSha256Pattern = Regex("[0-9A-F]{64}")

fun releaseSigningCertificateSha256(
    storeFile: File,
    storePassword: String,
    keyAlias: String,
): String {
    val password = storePassword.toCharArray()
    try {
        val keyStore = listOf("JKS", "PKCS12").firstNotNullOfOrNull { storeType ->
            runCatching {
                KeyStore.getInstance(storeType).apply {
                    storeFile.inputStream().use { input -> load(input, password) }
                }
            }.getOrNull()
        } ?: error("RELEASE_SIGNING_KEYSTORE_UNREADABLE")
        val certificate = keyStore.getCertificate(keyAlias)
            ?: error("RELEASE_SIGNING_ALIAS_CERTIFICATE_MISSING")
        return MessageDigest.getInstance("SHA-256")
            .digest(certificate.encoded)
            .joinToString(separator = "") { byte ->
                "%02X".format(byte.toInt() and 0xFF)
            }
    } finally {
        password.fill('\u0000')
    }
}

android {
    namespace = "com.aneb.probe"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.aneb.probe"
        minSdk = 29 // CellInfoNr / 5G API 需要（设计文档 §5）
        targetSdk = 35
        versionCode = 46
        versionName = "0.5.14"
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

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

ksp {
    // Room schema 纳入版本库；迁移评审不再只依赖手写 SQL 与运行时发现。
    arg("room.schemaLocation", file("$projectDir/schemas").path)
    arg("room.incremental", "true")
}

val verifyReleaseSigning by tasks.registering {
    group = "verification"
    description = "Fail closed unless the configured release certificate is explicitly approved."
    doLast {
        check(approvedLocalReleaseSigningCertificateSha256.isNotEmpty()) {
            "PRODUCTION_RELEASE_SIGNER_UNPROVISIONED"
        }
        check(
            approvedLocalReleaseSigningCertificateSha256.all {
                releaseCertificateSha256Pattern.matches(it)
            },
        ) {
            "RELEASE_SIGNING_ALLOWLIST_INVALID"
        }
        check(releaseSigningReady) {
            "RELEASE_SIGNING_CREDENTIALS_INCOMPLETE"
        }
        val configuredPath = releaseStorePath!!
        check(File(configuredPath).isAbsolute) {
            "RELEASE_SIGNING_STORE_PATH_NOT_ABSOLUTE"
        }
        val storeFile = file(configuredPath).canonicalFile
        check(storeFile.isFile) {
            "RELEASE_SIGNING_STORE_NOT_FOUND"
        }
        val repositoryRoot = rootProject.projectDir.parentFile.canonicalFile.toPath()
        check(!storeFile.toPath().startsWith(repositoryRoot)) {
            "RELEASE_SIGNING_STORE_INSIDE_REPOSITORY"
        }
        val actualCertificateSha256 = releaseSigningCertificateSha256(
            storeFile = storeFile,
            storePassword = releaseStorePassword!!,
            keyAlias = releaseKeyAlias!!,
        )
        check(actualCertificateSha256 in approvedLocalReleaseSigningCertificateSha256) {
            "RELEASE_SIGNING_CERTIFICATE_NOT_APPROVED actual_sha256=$actualCertificateSha256"
        }
    }
}

val releaseSigningGatedTaskNames = setOf(
    "assembleRelease",
    "bundleRelease",
    "installRelease",
    "packageRelease",
    "packageReleaseBundle",
    "signReleaseBundle",
)

tasks.matching { it.name in releaseSigningGatedTaskNames }
    .configureEach {
        dependsOn(verifyReleaseSigning)
    }

val originBoundOracleMode = providers.gradleProperty("aneb.routeA.originBoundOracle").orNull
check(originBoundOracleMode == null || originBoundOracleMode == "true") {
    "aneb.routeA.originBoundOracle accepts only the exact value true"
}
tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    if (name == "testDebugUnitTest") {
        if (originBoundOracleMode == "true") {
            filter.includeTestsMatching("com.aneb.probe.ui.MainActivityNewIntentOverloadGuardTest")
        } else {
            exclude("com/aneb/probe/ui/MainActivityNewIntentOverloadGuardTest.class")
        }
    }

}

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
    testImplementation(libs.robolectric)
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
