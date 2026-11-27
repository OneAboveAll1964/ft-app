import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

val hasReleaseKeystore = keystoreProps.getProperty("storePassword") != null &&
    rootProject.file(keystoreProps.getProperty("storeFile", "ft-release.jks")).exists()

// The Play build is signed with an upload key; Google Play signs what users install.
val playKeystoreProps = Properties().apply {
    val f = rootProject.file("keystore-play.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val hasPlayKeystore = playKeystoreProps.getProperty("storePassword") != null &&
    rootProject.file(playKeystoreProps.getProperty("storeFile", "ft-upload.jks")).exists()

android {
    namespace = "app.ft"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.ft"
        minSdk = 29
        targetSdk = 36
        versionCode = 201
        versionName = "4.1.4-custom"
    }

    signingConfigs {
        create("release") {
            if (hasReleaseKeystore) {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile", "ft-release.jks"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
        create("upload") {
            if (hasPlayKeystore) {
                storeFile = rootProject.file(playKeystoreProps.getProperty("storeFile", "ft-upload.jks"))
                storePassword = playKeystoreProps.getProperty("storePassword")
                keyAlias = playKeystoreProps.getProperty("keyAlias")
                keyPassword = playKeystoreProps.getProperty("keyPassword")
            }
        }
    }

    flavorDimensions += "store"
    productFlavors {
        create("github") {
            dimension = "store"
            buildConfigField("boolean", "PLAY", "false")
        }
        create("playstore") {
            dimension = "store"
            applicationId = "com.shkomaghdid.ft"
            // Play needs a new code for every upload; 184 is already on Play.
            versionCode = 185
            buildConfigField("boolean", "PLAY", "true")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
        debug {
            // Default to Android SDK debug key so debug builds work cleanly out-of-the-box
            signingConfig = signingConfigs.getByName("debug")
        }
        create("unsignedTransit") {
            initWith(getByName("release"))
            signingConfig = null
            matchingFallbacks += listOf("release")
        }
    }

    buildFeatures {
        compose = true
        aidl = true
        buildConfig = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += setOf("META-INF/*.version", "META-INF/AL2.0", "META-INF/LGPL2.1", "META-INF/versions/9/OSGI-INF/MANIFEST.MF")
    }
}

androidComponents {
    onVariants(selector().withFlavor("store" to "github").withBuildType("release")) { variant ->
        if (hasReleaseKeystore) {
            variant.signingConfig.setConfig(android.signingConfigs.getByName("release"))
        }
    }
    onVariants(selector().withFlavor("store" to "playstore").withBuildType("release")) { variant ->
        if (hasPlayKeystore) {
            variant.signingConfig.setConfig(android.signingConfigs.getByName("upload"))
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        freeCompilerArgs.add("-opt-in=androidx.compose.material3.ExperimentalMaterial3ExpressiveApi")
        freeCompilerArgs.add("-opt-in=androidx.compose.material3.ExperimentalMaterial3Api")
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.mockito:mockito-core:5.14.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("org.json:json:20240303")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.material3:material3:1.4.0-alpha18")
    implementation("androidx.compose.material:material-icons-core:1.7.8")
    implementation("androidx.compose.ui:ui:1.8.2")
    implementation("androidx.compose.foundation:foundation:1.8.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.1")
    implementation("androidx.savedstate:savedstate-ktx:1.3.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("androidx.media3:media3-exoplayer:1.8.0")
    implementation("com.google.zxing:core:3.5.3")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    implementation(project(":server"))
}
