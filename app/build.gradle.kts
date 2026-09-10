plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ro.e92.launcher"
    compileSdk = 34

    defaultConfig {
        applicationId = "ro.e92.launcher"
        minSdk = 27
        targetSdk = 27
        versionCode = 1
        versionName = "0.1"

        // Hardware unic: 1280x480. Nicio variantă de resurse, niciun ABI extra.
        resourceConfigurations += setOf("en")
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Uz personal: semnăm release cu cheia de debug ca APK-ul de pe stick
            // să se instaleze fără keystore separat. Schimbă dacă vrei altceva.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        viewBinding = true
    }

    lint {
        // Aplicația nu ajunge niciodată în Google Play: se instalează cu adb pe o
        // singură unitate. targetSdk 27 e o alegere, nu o scăpare — ridicarea lui
        // ar strica exact lucrurile de care depinde launcher-ul pe Android 8.1
        // (immersive sticky, comportamentul de HOME, permisiunile de overlay).
        // Fără linia asta, `assembleRelease` eșuează la lintVital, deși APK-ul
        // e deja construit și bun.
        disable += "ExpiredTargetSdkVersion"
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
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("androidx.core:core-ktx:1.12.0")
    // ComponentActivity (lifecycleScope) fără a trage AppCompat — temă de sistem, APK mai mic.
    implementation("androidx.activity:activity-ktx:1.7.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
}
