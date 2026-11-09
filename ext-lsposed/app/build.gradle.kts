plugins {
    alias(libs.plugins.agp.app)
}

android {
    namespace = "io.github.mineapp.ext"
    // libxposed api 102 的 AAR 声明了 minCompileSdk=37
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.mineapp.ext"
        minSdk = 26
        targetSdk = 36
        versionCode = 4
        versionName = "1.0.3"
    }

    buildTypes {
        release {
            // 体积本来就很小；不混淆可省掉 JS 接口 / 反射调用 Shizuku.newProcess 的 keep 规则
            isMinifyEnabled = false
            signingConfig = signingConfigs["debug"]
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        // API 102 的类文件用到了 sealed / record，javac 至少要 17
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        checkReleaseBuilds = false
    }

    packaging {
        resources {
            merges += "META-INF/xposed/*"
            excludes += "**"
        }
    }
}

dependencies {
    compileOnly(libs.libxposed.api)
    implementation(libs.libxposed.service)
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
}
