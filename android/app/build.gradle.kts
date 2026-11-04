import javax.inject.Inject

plugins { id("com.android.application") }

// Phones are arm64. `-Pgraphite.x86_64=true` adds x86_64 for the emulator harness on Linux CI runners.
val graphiteAbis = listOf("arm64-v8a") + if (providers.gradleProperty("graphite.x86_64").orNull == "true") listOf("x86_64") else emptyList()

android {
    namespace = "io.github.storskegg.graphite"
    compileSdk = 37
    ndkVersion = "30.0.16248370"
    defaultConfig {
        applicationId = "io.github.storskegg.graphite"
        minSdk = 30
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += graphiteAbis }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    // uniffi's Kotlin bindings call into the Rust core through JNA.
    implementation("net.java.dev.jna:jna:5.19.1@aar")
    testImplementation("junit:junit:4.13.2")
    // Device tests (app/src/androidTest): what MediaStore does with dates, on an emulator.
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}

/**
 * Builds the Rust core (`core-ffi/`, which wraps `core/` and `strip/`) for arm64 with the
 * NDK, then generates its Kotlin bindings with uniffi from the built library.
 */
abstract class RustCore : DefaultTask() {
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    @get:Internal abstract val crate: DirectoryProperty
    @get:Internal abstract val ndk: DirectoryProperty
    @get:Input abstract val cargo: Property<String>
    @get:Input abstract val abis: ListProperty<String>

    @get:OutputDirectory abstract val jniLibs: DirectoryProperty
    @get:OutputDirectory abstract val kotlin: DirectoryProperty

    @get:Inject abstract val exec: ExecOperations

    @TaskAction
    fun build() {
        val dir = crate.get().asFile
        val libs = jniLibs.get().asFile.apply { deleteRecursively(); mkdirs() }
        exec.exec {
            workingDir = dir
            environment("ANDROID_NDK_HOME", ndk.get().asFile.path)
            commandLine(listOf(cargo.get(), "ndk") + abis.get().flatMap { listOf("-t", it) } +
                listOf("-P", "30", "-o", libs.path, "build", "--release", "--locked"))
        }
        val out = kotlin.get().asFile.apply { deleteRecursively(); mkdirs() }
        exec.exec {
            workingDir = dir
            commandLine(cargo.get(), "run", "--quiet", "--locked", "--features", "cli",
                "--bin", "uniffi-bindgen", "--", "generate",
                "--library", File(libs, "arm64-v8a/libgraphite_ffi.so").path,
                "--language", "kotlin", "--no-format", "--out-dir", out.path)
        }
    }
}

val repo: File = rootDir.parentFile
val rustCore = tasks.register<RustCore>("rustCore") {
    crate.set(File(repo, "core-ffi"))
    ndk.set(androidComponents.sdkComponents.ndkDirectory)
    abis.set(graphiteAbis)
    cargo.set(listOf(System.getenv("CARGO"), "${System.getProperty("user.home")}/.cargo/bin/cargo")
        .firstOrNull { it != null && File(it).canExecute() } ?: "cargo")
    sources.from(
        fileTree(File(repo, "core")) { include("src/**", "Cargo.toml", "Cargo.lock") },
        fileTree(File(repo, "strip")) { include("src/**", "Cargo.toml", "Cargo.lock") },
        fileTree(File(repo, "core-ffi")) { include("src/**", "Cargo.toml", "Cargo.lock", "uniffi.toml") },
    )
    jniLibs.set(layout.buildDirectory.dir("generated/rust/jniLibs"))
    kotlin.set(layout.buildDirectory.dir("generated/rust/kotlin"))
}

androidComponents.onVariants { variant ->
    variant.sources.jniLibs?.addGeneratedSourceDirectory(rustCore, RustCore::jniLibs)
    variant.sources.kotlin?.addGeneratedSourceDirectory(rustCore, RustCore::kotlin)
}
