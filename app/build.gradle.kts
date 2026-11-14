plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.chaquo.python")
}

android {
    namespace = "app.docscan"
    compileSdk = 34

    defaultConfig {
        applicationId = "app.docscan"
        minSdk = 29
        targetSdk = 34
        // GitHub Actions sets BUILD_NUMBER and GITHUB_REPOSITORY: every build installs over the previous one, and the app
        // knows its own build number + repository, so it can look for a newer release (like Image Fixer does).
        // On your own computer both are empty: a development copy, which never offers updates.
        val build = (System.getenv("BUILD_NUMBER") ?: "0").toInt()
        versionCode = if (build > 0) build else 1
        versionName = if (build > 0) "1.0.$build" else "dev"
        buildConfigField("int", "BUILD", build.toString())
        buildConfigField("String", "REPO", "\"" + (System.getenv("GITHUB_REPOSITORY") ?: "") + "\"")
        ndk { abiFilters += listOf("arm64-v8a") }   // practically every phone since 2017
    }

    signingConfigs {
        create("release") {
            storeFile = rootProject.file("keystore/docscan.jks")
            storePassword = "docscan123"
            keyAlias = "docscan"
            keyPassword = "docscan123"
        }
    }
    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        // this Compose version still marks pager (and a few Material3 parts) as experimental: allow them everywhere
        freeCompilerArgs += listOf(
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
        )
    }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

chaquopy {
    defaultConfig {
        version = "3.11"
        pip {
            install("numpy")
            install("pillow")
            // The PC program's page finder needs OpenCV. If Chaquopy has no OpenCV wheel the workflow
            // builds once more with -PnoCv=true (the older, slower finder is used then).
            if (!project.hasProperty("noCv")) install("opencv-python-headless")
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.3")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.documentfile:documentfile:1.0.1")
    val camerax = "1.3.4"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")
}
