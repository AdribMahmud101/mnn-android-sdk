plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    `maven-publish`
}

android {
    namespace = "com.mnn.sdk"
    compileSdk = project.property("COMPILE_SDK_VERSION").toString().toInt()
    
    defaultConfig {
        minSdk = project.property("MIN_SDK_VERSION").toString().toInt()
        
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
        
        // Native library configuration
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
        
        // CMake configuration for JNI bridge
        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DANDROID_STL=c++_static",
                    "-DANDROID_PLATFORM=android-21"
                )
                cppFlags += listOf("-std=c++17", "-frtti", "-fexceptions")
            }
        }
    }
    
    packagingOptions {
        // Exclude libc++_shared from CMake, we'll use the one from jniLibs that matches libMNN.so
        jniLibs {
            pickFirsts.add("**/libc++_shared.so")
        }
    }
    
    // CMake build configuration
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
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
        freeCompilerArgs = listOf(
            "-Xopt-in=kotlin.RequiresOptIn",
            "-Xopt-in=kotlinx.coroutines.ExperimentalCoroutinesApi"
        )
    }
    
    sourceSets {
        getByName("main") {
            // Include prebuilt native libraries
            jniLibs.srcDirs("src/main/jniLibs")
        }
    }
    
    publishing {
        singleVariant("release") {
            withSourcesJar()
            withJavadocJar()
        }
    }
}

dependencies {
    // Kotlin
    implementation("org.jetbrains.kotlin:kotlin-stdlib:${project.property("KOTLIN_VERSION")}")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:${project.property("COROUTINES_VERSION")}")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:${project.property("COROUTINES_VERSION")}")
    
    // AndroidX
    implementation("androidx.core:core-ktx:${project.property("ANDROIDX_CORE_VERSION")}")
    implementation("androidx.annotation:annotation:1.7.0")
    
    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:${project.property("COROUTINES_VERSION")}")
    testImplementation("io.mockk:mockk:1.13.8")
    
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}

publishing {
    publications {
        create<MavenPublication>("release") {
            groupId = "com.mnn"
            artifactId = "mnn-sdk"
            version = project.property("VERSION_NAME").toString()
            
            afterEvaluate {
                from(components["release"])
            }
            
            pom {
                name.set("MNN Android SDK")
                description.set("Easy-to-use Kotlin Android SDK for MNN (Mobile Neural Network) inference framework")
                url.set("https://github.com/alibaba/MNN")
                
                licenses {
                    license {
                        name.set("Apache License 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0")
                    }
                }
            }
        }
    }
}
