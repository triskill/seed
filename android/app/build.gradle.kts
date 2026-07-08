// App module: the only module in Phase 5. We keep it
// monolithic on purpose — multi-module Android projects
// pay a meaningful build-time tax for module boundaries
// and the app is too small to need them. When (if) the
// app grows past ~50 KLOC or the build time becomes a
// real bottleneck, the natural split is
// `:core:chat:api` / `:core:webview:api` / `:feature:app`
// / `:feature:chat` / `:feature:shell` / `:feature:settings`.

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.seed.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.seed.app"
        // 26 = Android 8.0 Oreo. Aligns with the plan's
        // minSdk (also lets us rely on adaptive icons,
        // notification channels, and the modern JobScheduler
        // API without compat shims).
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        // The dev backend runs on the host machine; on
        // emulator we use the special 10.0.2.2 alias.
        // On a physical device we'd need a real LAN IP.
        // Phase 5.3 (WebView) reads this from BuildConfig.
        buildConfigField("String", "WEBAPP_DEV_URL", "\"http://10.0.2.2:7778/\"")
        buildConfigField("String", "BACKEND_DEV_URL", "\"http://10.0.2.2:7777/\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        debug {
            // Default: dev URLs from BuildConfig above.
            // Phase 5.3 will switch the WebView to these
            // at runtime so the app works on physical
            // devices too.
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
        buildConfig = true
    }

    composeOptions {
        // The Compose compiler is shipped as a separate
        // artifact and its version is pinned to match the
        // Kotlin version (1.9.24 here). See
        // https://developer.android.com/jetpack/androidx/releases/compose-kotlin
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    // The runtime assets (proot binary, rootfs tarball,
    // seed_version.json) are read by AssetManager.openFd
    // — see AndroidAssetSource.entries(). openFd throws
    // FileNotFoundException on compressed entries, so we
    // have to keep these uncompressed in the APK. The
    // rootfs is already gzipped, so storing it
    // uncompressed in the APK costs no extra space and
    // extraction is faster (no deflate at install time).
    // The patterns are matched against paths under
    // `assets/` (not the full APK path), so the
    // `linux/` prefix is what AAPT actually sees.
    //
    // Naming trap: the source asset is `rootfs.tar.gz`,
    // but the Android Gradle Plugin's CompressAssetsTask
    // auto-decompresses `.gz` files in `assets/` and
    // strips the extension, so the merged asset on disk
    // (and inside the APK) is `rootfs.tar`. The pattern
    // must match the merged name, not the source name —
    // using `linux/rootfs.tar.gz` here would let the
    // 348 MB tarball slip through DEFLATE compression
    // and crash the app on first launch with "This file
    // can not be opened as a file descriptor; it is
    // probably compressed".
    androidResources {
        noCompress += listOf(
            "linux/seed_version.json",
            "linux/proot",
            "linux/rootfs.tar",
        )
    }
}

dependencies {
    // Core / lifecycle
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.2")
    implementation("androidx.activity:activity-compose:1.9.0")

    // Compose BOM keeps every Compose artifact in sync.
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // Navigation (Task 5.2)
    implementation("androidx.navigation:navigation-compose:2.7.7")

    // WebView is in the platform android.webkit; the
    // androidx.webkit:webkit artifact provides
    // compatibility shims and the new
    // `WebViewCompat` / `WebViewFeature` APIs we'll use
    // in Phase 5.3.
    implementation("androidx.webkit:webkit:1.11.0")

    // SwipeRefreshLayout for the App tab's
    // pull-to-refresh (Phase 5.3). Compose's
    // `PullToRefreshBox` is only in material3 1.3+,
    // and our Compose BOM (2024.06.00) ships
    // material3 1.2.1. SwipeRefreshLayout is the
    // stable, well-known option and matches the
    // plan's suggestion. We wrap it in `AndroidView`
    // alongside the WebView.
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")

    // DataStore-Preferences (Phase 5.7) — the
    // non-secret fields of the Settings form
    // (provider, model, ports, log level) are
    // stored here. Typed key access
    // (stringPreferencesKey / intPreferencesKey) +
    // a coroutine-friendly read/write API. The
    // API key is stored in EncryptedSharedPreferences
    // (see security-crypto below) so the secret
    // never touches the plain-text DataStore file.
    implementation("androidx.datastore:datastore-preferences:1.0.0")

    // Security-Crypto (Phase 5.7) — wraps
    // `EncryptedSharedPreferences`, which AES-encrypts
    // the prefs file at rest using a master key in
    // the Android Keystore. We use this for the
    // Settings form's `apiKey` field only (one
    // key-value pair, but the encryption boundary
    // is the whole prefs file so we pay no extra
    // per-key cost). 1.1.0 is the stable release
    // (March 2024) — the API has been stable since
    // 1.0.0 (2020) and 1.1.0 only adds a few
    // opt-in features we don't use.
    implementation("androidx.security:security-crypto:1.1.0")

    // Phase 6.1: Retrofit + OkHttp + Moshi — the
    // HTTP client the Android app uses to talk to
    // the FastAPI backend. Retrofit 2.11.0 has
    // first-class `suspend` support (so the
    // [BackendApi] interface is plain `suspend fun`
    // declarations, no Call<...> wrapping). OkHttp
    // 4.12.0 is the latest stable in the 4.x line
    // (5.x is still alpha as of 2024). The logging
    // interceptor is wired only in debug builds
    // (see [ApiModule]) so release APKs don't
    // leak request/response bodies to logcat.
    //
    // Moshi 1.15.1 + moshi-kotlin + kotlin-reflect:
    // we use Moshi's `KotlinJsonAdapterFactory` to
    // deserialize the DTOs in [BackendApi]. This
    // requires `kotlin-reflect` at runtime; the
    // alternative (Moshi codegen via KSP) needs a
    // build plugin and is overkill for ~3 DTOs.
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-moshi:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("com.squareup.moshi:moshi:1.15.1")
    implementation("com.squareup.moshi:moshi-kotlin:1.15.1")
    implementation("org.jetbrains.kotlin:kotlin-reflect:1.9.24")

    // Debug / tooling (not packaged in release builds).
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // Unit tests (run on JVM, no emulator).
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    // Phase 6.1: MockWebServer for the [BackendApi]
    // contract tests. Lives in OkHttp's test artifact
    // (not the main one) so it doesn't end up in
    // production APKs.
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")

    // Instrumented tests (require emulator / device).
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}
