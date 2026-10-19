import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing details live in android/keystore.properties (git-ignored), e.g.
//   storeFile=C:/Users/<you>/.fps-monitor-keys/release.jks
//   storePassword=...
//   keyAlias=fps-monitor
//   keyPassword=...
// Without it, release builds are signed with the local debug key: fine for
// testing on your own phone, never for publishing.
val keystoreFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystoreFile.exists()) keystoreFile.inputStream().use(::load)
}
// The properties format treats "\" as an escape, which mangles Windows paths
// like C:\Users\... (even "\r" becomes a line break). Read storeFile as written.
val storeFilePath: String? = if (keystoreFile.exists()) {
    keystoreFile.readLines().firstOrNull { it.trimStart().startsWith("storeFile") }
        ?.substringAfter("=")?.trim()
} else null
val hasReleaseKey = storeFilePath != null

android {
    namespace = "dev.fpsmonitor"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.fpsmonitor"
        // Built for one Android 16 phone; 34 is the first with the
        // "specialUse" foreground-service type the monitor needs.
        minSdk = 34
        targetSdk = 35
        versionCode = 4
        versionName = "0.3.1"
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = file(storeFilePath!!)
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // Not debuggable (the default for release), shrunk and optimised.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName(if (hasReleaseKey) "release" else "debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
}
