import java.util.Base64

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.jetbrains.kotlin.android)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.ktlint)
}

ktlint {
    android = true
    verbose = true
}

android {
    namespace = "com.karoocameracontrol"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.karoocameracontrol"
        minSdk = 23
        targetSdk = 34
        versionCode = (System.getenv("BUILD_NUMBER")?.toInt() ?: 1)
        versionName = System.getenv("RELEASE_VERSION") ?: "1.0"
        testInstrumentationRunner = "com.karoocameracontrol.DescentUiInstrumentation"
    }

    signingConfigs {
        create("release") {
            val env: MutableMap<String, String> = System.getenv()
            keyAlias = env["KEY_ALIAS"]
            keyPassword = env["KEY_PASSWORD"]

            val base64keystore: String = env["KEYSTORE_BASE64"] ?: ""
            val keystoreFile: File = File.createTempFile("keystore", ".jks")
            keystoreFile.writeBytes(Base64.getDecoder().decode(base64keystore))
            storeFile = keystoreFile
            storePassword = env["KEYSTORE_PASSWORD"]
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            manifestPlaceholders["releaseVersion"] = "latest"
        }
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            manifestPlaceholders["releaseVersion"] = System.getenv("RELEASE_VERSION").takeUnless { it.isNullOrBlank() } ?: "latest"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    buildFeatures {
        compose = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8)
    }
}

dependencies {
    implementation(libs.hammerhead.karoo.ext)
    implementation(libs.androidx.core.ktx)
    implementation(libs.bundles.androidx.lifeycle)
    implementation(libs.androidx.activity.compose)
    implementation(libs.bundles.compose.ui)
    implementation(libs.androidx.compose.material.icons.extended)
}

// These checks use only the Kotlin/coroutines libraries already used by the app.
val verifyDescentRecording by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Runs deterministic descent detection and camera command checks"
    dependsOn("compileDebugUnitTestKotlin")
    mainClass.set("com.karoocameracontrol.recording.DescentRecordingChecks")
    classpath =
        files(
            layout.buildDirectory.dir("tmp/kotlin-classes/debugUnitTest"),
            layout.buildDirectory.dir("tmp/kotlin-classes/debug"),
        ) + configurations.getByName("debugUnitTestRuntimeClasspath")
}

tasks.named("check") {
    dependsOn(verifyDescentRecording)
}
