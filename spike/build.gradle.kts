plugins { id("com.android.application") version "9.4.1" }

android {
    namespace = "io.github.storskegg.graphite.spike"
    compileSdk = 37
    defaultConfig {
        applicationId = "io.github.storskegg.graphite.spike"
        minSdk = 33
        targetSdk = 37
        versionCode = 1
        versionName = "0.1"
    }
}
