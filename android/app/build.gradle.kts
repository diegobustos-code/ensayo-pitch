plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val runNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "cl.fireproof.ensayopitch"
    compileSdk = 34

    defaultConfig {
        applicationId = "cl.fireproof.ensayopitch"
        minSdk = 24
        targetSdk = 34
        versionCode = runNumber
        versionName = "2.0.$runNumber"
    }

    signingConfigs {
        create("equipo") {
            // Llave de firma de la app del curso. Va en el repo para que cada versión
            // se instale encima de la anterior sin perder el historial.
            storeFile = rootProject.file("keystore/ensayo.jks")
            storePassword = "EnsayoPitchFireProof"
            keyAlias = "ensayo"
            keyPassword = "EnsayoPitchFireProof"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("equipo")
        }
        debug {
            signingConfig = signingConfigs.getByName("equipo")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation("com.alphacephei:vosk-android:0.3.47@aar")
    implementation("net.java.dev.jna:jna:5.13.0@aar")
    implementation("androidx.core:core:1.13.1")
    testImplementation("junit:junit:4.13.2")
}
