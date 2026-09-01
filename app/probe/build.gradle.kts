import java.security.KeyStore
import java.security.MessageDigest
import java.util.Locale

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
val approvedReleaseKeyAlias = "aneb-production"
val approvedReleaseCertificateSha256 = "b33df25ffa3b1bedc7e08af77b442bc205aeab553c07ee72344e19ed0f7b6003"
val prototypeSourceCommit = providers.gradleProperty("aneb.prototype.sourceCommit")
    .orElse(providers.environmentVariable("ANEB_PROTOTYPE_SOURCE_COMMIT"))
    .orNull
val prototypeSourceCommitPattern = Regex("^[0-9a-f]{40}$")
val prototypeSourceCommitForBuildConfig = prototypeSourceCommit
    ?.takeIf(prototypeSourceCommitPattern::matches)
    .orEmpty()

android {
    namespace = "com.aneb.probe"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.aneb.probe"
        minSdk = 29 // CellInfoNr / 5G API 需要（设计文档 §5）
        targetSdk = 35
        versionCode = 20
        versionName = "0.2.0"
        buildConfigField("boolean", "PROTOTYPE_ENGINEERING", "false")
        buildConfigField("boolean", "PROTOTYPE_RELEASE", "false")
        buildConfigField("boolean", "PROTOTYPE_PRIVATE_CLEARTEXT", "false")
        buildConfigField("String", "PROTOTYPE_SOURCE_COMMIT", "\"$prototypeSourceCommitForBuildConfig\"")
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
            buildConfigField("boolean", "PROTOTYPE_PRIVATE_CLEARTEXT", "true")
            // 明文流量仅经 src/debug/res/xml/network_security_config.xml 允许（仿真服务器联调）
            // release 变体不带该配置，targetSdk>=28 默认禁明文
        }
        create("prototypeEngineering") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".prototype"
            versionNameSuffix = "-prototype-engineering"
            matchingFallbacks += listOf("debug")
            buildConfigField("boolean", "PROTOTYPE_ENGINEERING", "true")
            buildConfigField("boolean", "PROTOTYPE_PRIVATE_CLEARTEXT", "true")
        }
        create("prototypeRelease") {
            initWith(getByName("release"))
            matchingFallbacks += listOf("release")
            buildConfigField("boolean", "PROTOTYPE_ENGINEERING", "false")
            buildConfigField("boolean", "PROTOTYPE_RELEASE", "true")
            buildConfigField("boolean", "PROTOTYPE_PRIVATE_CLEARTEXT", "true")
            if (releaseSigningReady) {
                signingConfig = signingConfigs.getByName("release")
            }
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
        check(file(releaseStorePath!!).isFile) { "P010_RELEASE_SIGNING_STORE_UNAVAILABLE" }
        check(releaseKeyAlias == approvedReleaseKeyAlias) {
            "P011_RELEASE_SIGNER_NOT_APPROVED"
        }

        val storePasswordChars = releaseStorePassword!!.toCharArray()
        val keyStore = try {
            val loadedKeyStore = KeyStore.getInstance("PKCS12")
            file(releaseStorePath).inputStream().buffered().use { input ->
                loadedKeyStore.load(input, storePasswordChars)
            }
            loadedKeyStore
        } catch (_: Exception) {
            throw GradleException("P010_RELEASE_SIGNING_STORE_UNAVAILABLE")
        } finally {
            storePasswordChars.fill('\u0000')
        }
        check(keyStore.isKeyEntry(releaseKeyAlias)) {
            "P011_RELEASE_SIGNER_NOT_APPROVED"
        }
        val certificate = keyStore.getCertificate(releaseKeyAlias)
        check(certificate != null) {
            "P011_RELEASE_SIGNER_NOT_APPROVED"
        }
        val certificateSha256 = MessageDigest.getInstance("SHA-256")
            .digest(certificate.encoded)
            .joinToString("") { byte ->
                "%02x".format(Locale.ROOT, byte.toInt() and 0xff)
            }
        check(certificateSha256 == approvedReleaseCertificateSha256) {
            "P011_RELEASE_SIGNER_NOT_APPROVED"
        }
    }
}

val verifyPrototypeSourceCommit by tasks.registering {
    group = "verification"
    description = "Fail closed when the Prototype APK source commit is not exact."
    doLast {
        check(prototypeSourceCommit?.matches(prototypeSourceCommitPattern) == true) {
            "P019_PROTOTYPE_SOURCE_COMMIT_NOT_BOUND"
        }
    }
}

val releaseArtifactTaskNames = setOf(
    "packageRelease",
    "packagePrototypeRelease",
    "packageReleaseBundle",
    "packagePrototypeReleaseBundle",
    "signReleaseBundle",
    "signPrototypeReleaseBundle",
    "packageReleaseUniversalApk",
    "packagePrototypeReleaseUniversalApk",
)
val prototypeReleaseArtifactTaskNames = releaseArtifactTaskNames.filterTo(mutableSetOf()) {
    it.contains("Prototype", ignoreCase = false)
}

tasks.matching {
    it.name in setOf(
        "assembleRelease",
        "bundleRelease",
        "installRelease",
        "assemblePrototypeRelease",
        "bundlePrototypeRelease",
        "installPrototypeRelease",
    ) || it.name in releaseArtifactTaskNames
}
    .configureEach { dependsOn(verifyReleaseSigning) }

tasks.matching {
    it.name in setOf(
        "assemblePrototypeRelease",
        "bundlePrototypeRelease",
        "installPrototypeRelease",
    ) || it.name in prototypeReleaseArtifactTaskNames
}
    .configureEach { dependsOn(verifyPrototypeSourceCommit) }

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

    // 阶段 2 API 探针：key 存 EncryptedSharedPreferences（初始化失败退私有明文 prefs，
    // 见 ApiKeyStore KDoc 取舍说明）
    implementation(libs.androidx.security.crypto)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    testImplementation(libs.junit)
    testImplementation("org.robolectric:robolectric:4.16.1")
}
