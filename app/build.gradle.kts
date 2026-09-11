plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// 应用版本：每次发布更新版本名称，并递增内部版本编号。
val appVersionName = "0.1.2"
val appVersionCode = 3

val buildEngine by tasks.registering(Exec::class) {
    group = "build"
    description = "Build the pinned Go mobile business library for ARM64 and x86_64."
    workingDir(rootDir)
    val python = providers.gradleProperty("jeemi.python").orElse(
        if (System.getProperty("os.name").startsWith("Windows")) "python" else "python3"
    )
    commandLine(python.get(), "scripts/dev.py", "engine-build")
    inputs.files(fileTree(rootDir.resolve("engine")) {
        include("**/*.go", "go.mod", "go.sum")
        exclude("build/**")
    })
    inputs.files(rootDir.resolve("scripts/dev.py"), rootDir.resolve("scripts/toolchain.json"))
    outputs.file(rootDir.resolve("engine/build/jeemi-engine.aar"))
}

val verifyBundledCore by tasks.registering(Exec::class) {
    group = "verification"
    workingDir(rootDir)
    commandLine(providers.gradleProperty("jeemi.python").getOrElse(if (System.getProperty("os.name").startsWith("Windows")) "python" else "python3"),
        "scripts/mihomo.py", "verify")
    inputs.dir(rootDir.resolve("resources/mihomo"))
    inputs.file(rootDir.resolve("scripts/mihomo.py"))
}
tasks.named("preBuild") { dependsOn(verifyBundledCore) }
val verifyBundledGeo by tasks.registering(Exec::class) {
    group = "verification"
    workingDir(rootDir)
    commandLine(providers.gradleProperty("jeemi.python").getOrElse(if (System.getProperty("os.name").startsWith("Windows")) "python" else "python3"),
        "scripts/geodata.py", "verify")
    inputs.dir(rootDir.resolve("resources/geodata"))
    inputs.file(rootDir.resolve("scripts/geodata.py"))
}
tasks.named("preBuild") { dependsOn(verifyBundledGeo) }

android {
    namespace = "io.jeemi.android"
    compileSdk = 36
    ndkVersion = "27.2.12479018"
    defaultConfig {
        applicationId = "io.jeemi.android"
        minSdk = 26
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildTypes {
        debug { applicationIdSuffix = ".debug"; versionNameSuffix = "-dev" }
        release {
            isMinifyEnabled = true
            // CI records the public revision in release.json; local APKs must not expose private Git metadata.
            vcsInfo { include = false }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true; buildConfig = true }
    androidResources { localeFilters += listOf("en", "zh-rCN") }
    sourceSets.getByName("main") {
        jniLibs.srcDir(rootDir.resolve("resources/mihomo/jniLibs"))
        assets.srcDir(rootDir.resolve("resources/notices"))
        assets.srcDir(rootDir.resolve("resources/geodata/assets"))
    }
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "x86_64")
            isUniversalApk = false
        }
    }
    packaging {
        // Android 10+ forbids exec from writable filesDir. PackageManager extracts
        // this ABI's executable into nativeLibraryDir; never load it as JNI.
        jniLibs.useLegacyPackaging = true
        jniLibs.keepDebugSymbols += "**/libmihomo_exec.so"
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
    lint { abortOnError = true; warningsAsErrors = false }
}
kotlin { jvmToolchain(21) }
kotlin.compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)

dependencies {
    implementation(files(rootDir.resolve("engine/build/jeemi-engine.aar")).builtBy(buildEngine))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.icons)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.zxing.embedded)
    debugImplementation(libs.compose.ui.tooling)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    // Compose's transitive Espresso 3.5.0 uses APIs removed on Android 16.
    androidTestImplementation(libs.androidx.test.espresso)
    debugImplementation(libs.compose.ui.test.manifest)
}
