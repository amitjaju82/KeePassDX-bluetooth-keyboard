import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.ksp)
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

android {
    namespace = "com.kunzisoft.keepass"
    compileSdk = 36

    val gmsPackage = "com.google.android.gms"

    defaultConfig {
        // Deliberately distinct from upstream's com.kunzisoft.keepass: this fork installs
        // alongside KeePassDX rather than colliding with it, and can never be mistaken for an
        // update to the upstream app. The namespace below stays upstream's, so R and
        // BuildConfig are unchanged and rebases stay clean.
        applicationId = "com.kunzisoft.keepass.bt"
        minSdk = 19
        targetSdk = 36
        // Fork versioning: <upstream version>-bt<fork release>. The version code keeps the
        // upstream numbering and adds the fork release in the last two digits, so it stays
        // ordered against upstream and leaves room for upstream rebases.
        versionCode = 45200_01
        versionName = "4.5.2-bt1"
        multiDexEnabled = true

        testApplicationId = "com.kunzisoft.keepass.tests"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "GOOGLE_PLAY_SERVICES_PACKAGE", "\"$gmsPackage\"")
        buildConfigField("String[]", "ICON_PACKS", "{\"classic\",\"material\"}")
        
        manifestPlaceholders["googleAndroidBackupAPIKey"] = "unused"
        manifestPlaceholders["googlePlayServicesPackage"] = gmsPackage
    }

    /*
     * Release signing.
     *
     * Credentials come from local.properties (gitignored) or the environment, never from the
     * repository. If they are absent the release build is simply left unsigned rather than
     * failing, so anyone can still clone and build a debug APK without a key.
     *
     * Set in local.properties:
     *   releaseStoreFile=C:/Users/you/keys/keepassdx-bt-release.jks
     *   releaseStorePassword=...
     *   releaseKeyAlias=keepassdx-bt
     *   releaseKeyPassword=...
     * or as env vars KEEPASSDX_BT_STORE_FILE / _STORE_PASSWORD / _KEY_ALIAS / _KEY_PASSWORD.
     */
    val localProps = Properties().apply {
        rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
    }
    fun signingValue(propKey: String, envKey: String): String? =
        localProps.getProperty(propKey) ?: System.getenv(envKey)

    val releaseStoreFile = signingValue("releaseStoreFile", "KEEPASSDX_BT_STORE_FILE")
    val hasReleaseSigning = releaseStoreFile != null && file(releaseStoreFile).exists()

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseStoreFile!!)
                storePassword = signingValue("releaseStorePassword", "KEEPASSDX_BT_STORE_PASSWORD")
                keyAlias = signingValue("releaseKeyAlias", "KEEPASSDX_BT_KEY_ALIAS")
                keyPassword = signingValue("releaseKeyPassword", "KEEPASSDX_BT_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            } else {
                logger.warn(
                    "No release signing key configured; the release APK will be unsigned. " +
                    "See the signing block in app/build.gradle.kts."
                )
            }
        }
    }

    buildFeatures {
        buildConfig = true
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    flavorDimensions += "version"
    productFlavors {
        create("libre") {
            dimension = "version"
            applicationIdSuffix = ".libre"
            buildConfigField("String", "BUILD_VERSION", "\"libre\"")
            buildConfigField("boolean", "CLOSED_STORE", "false")
            buildConfigField(
                "String[]", "STYLES_DISABLED",
                "{\"KeepassDXStyle_Red\"," +
                    "\"KeepassDXStyle_Red_Night\"," +
                    "\"KeepassDXStyle_Reply\"," +
                    "\"KeepassDXStyle_Reply_Night\"," +
                    "\"KeepassDXStyle_Purple\"," +
                    "\"KeepassDXStyle_Purple_Dark\"," +
                    "\"KeepassDXStyle_Dynamic_Light\"," +
                    "\"KeepassDXStyle_Dynamic_Night\"}"
            )
            buildConfigField("String[]", "ICON_PACKS_DISABLED", "{}")
        }
        create("free") {
            dimension = "version"
            applicationIdSuffix = ".free"
            buildConfigField("String", "BUILD_VERSION", "\"free\"")
            buildConfigField("boolean", "CLOSED_STORE", "true")
            buildConfigField(
                "String[]", "STYLES_DISABLED",
                "{\"KeepassDXStyle_Blue\"," +
                    "\"KeepassDXStyle_Blue_Night\"," +
                    "\"KeepassDXStyle_Red\"," +
                    "\"KeepassDXStyle_Red_Night\"," +
                    "\"KeepassDXStyle_Reply\"," +
                    "\"KeepassDXStyle_Reply_Night\"," +
                    "\"KeepassDXStyle_Purple\"," +
                    "\"KeepassDXStyle_Purple_Dark\"," +
                    "\"KeepassDXStyle_Dynamic_Light\"," +
                    "\"KeepassDXStyle_Dynamic_Night\"}"
            )
            buildConfigField("String[]", "ICON_PACKS_DISABLED", "{}")
            manifestPlaceholders["googleAndroidBackupAPIKey"] = "AEdPqrEAAAAIbRfbV8fHLItXo8OcHwrO0sSNblqhPwkc0DPTqg"
            manifestPlaceholders["googlePlayServicesPackage"] = gmsPackage
        }
    }

    sourceSets {
        getByName("libre") {
            res.srcDirs("src/libre/res")
        }
        getByName("free") {
            res.srcDirs("src/free/res")
        }
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            pickFirsts.add("META-INF/versions/9/OSGI-INF/MANIFEST.MF")
        }
    }

    @Suppress("UnstableApiUsage")
    androidResources {
        generateLocaleConfig = true
    }
}

dependencies {
    implementation(libs.kotlin.stdlib)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.multidex)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.preference.ktx)
    implementation(libs.androidx.cardview)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.viewpager2)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.media)
    // Lifecycle - ViewModel - Coroutines
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.google.material)
    // Token auto complete
    // From sources until https://github.com/splitwise/TokenAutoComplete/pull/422 fixed
    implementation(libs.tokenautocomplete)
    // Database
    implementation(libs.androidx.room.runtime)
    ksp(libs.androidx.room.compiler)
    // Utilities
    implementation(libs.androidx.autofill)
    implementation(libs.joda.time)
    implementation(libs.chroma)
    implementation(libs.taptargetview)
    implementation(libs.commons.io)
    // Credentials
    implementation(libs.nbvcxz)
    implementation(libs.androidx.credentials)
    // Modules import
    implementation(project(":database"))
    implementation(project(":icon-pack"))
    // Tests
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
}
