plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.mnn.sample"
    compileSdk = project.property("COMPILE_SDK_VERSION").toString().toInt()
    
    defaultConfig {
        applicationId = "com.mnn.sample"
        minSdk = project.property("MIN_SDK_VERSION").toString().toInt()
        targetSdk = project.property("TARGET_SDK_VERSION").toString().toInt()
        versionCode = project.property("VERSION_CODE").toString().toInt()
        versionName = project.property("VERSION_NAME").toString()
        
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
        viewBinding = true
    }
}

dependencies {
    // MNN SDK
    implementation(project(":mnn-sdk"))
    
    // Kotlin
    implementation("org.jetbrains.kotlin:kotlin-stdlib:${project.property("KOTLIN_VERSION")}")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:${project.property("COROUTINES_VERSION")}")
    
    // Network & JSON
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.10.1")
    
    // AndroidX
    implementation("androidx.core:core-ktx:${project.property("ANDROIDX_CORE_VERSION")}")
    implementation("androidx.appcompat:appcompat:${project.property("APPCOMPAT_VERSION")}")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    
    // Testing
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}
