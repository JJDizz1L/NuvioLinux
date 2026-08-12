import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.TaskAction
import org.gradle.jvm.tasks.Jar
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask
import java.io.File
import java.util.Properties

abstract class GenerateRuntimeConfigsTask : DefaultTask() {
    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Optional
    @get:InputFile
    abstract val localPropertiesFile: RegularFileProperty

    @get:Input
    abstract val appVersionName: Property<String>

    @get:Input
    abstract val appVersionCode: Property<Int>

    @get:Input
    abstract val desktopAppVersionName: Property<String>

    @get:Input
    abstract val desktopAppVersionCode: Property<Int>

    @get:Input
    abstract val supabaseUrl: Property<String>

    @get:Input
    abstract val supabaseAnonKey: Property<String>

    @get:Input
    abstract val supabaseFallbackUrl: Property<String>

    @get:Input
    abstract val sentryDsn: Property<String>

    @get:Input
    abstract val sentryEnvironment: Property<String>

    @TaskAction
    fun generate() {
        val props = Properties()
        localPropertiesFile.asFile.orNull?.takeIf { it.exists() }?.inputStream()?.use { props.load(it) }

        val outDir = outputDir.get().asFile
        outDir.resolve("com/nuviolinux/app/core/network").apply {
            mkdirs()
            resolve("SupabaseConfig.kt").writeText(
                """
                |package com.nuviolinux.app.core.network
                |
                |object SupabaseConfig {
                |    const val URL = "${supabaseUrl.get()}"
                |    const val ANON_KEY = "${supabaseAnonKey.get()}"
                |    const val FALLBACK_URL = "${supabaseFallbackUrl.get()}"
                |}
                """.trimMargin()
            )
        }

        outDir.resolve("com/nuviolinux/app/core/diagnostics").apply {
            mkdirs()
            resolve("SentryConfig.kt").writeText(
                """
                |package com.nuviolinux.app.core.diagnostics
                |
                |object SentryConfig {
                |    const val DSN = "${sentryDsn.get()}"
                |    const val ENVIRONMENT = "${sentryEnvironment.get()}"
                |}
                """.trimMargin()
            )
        }

        outDir.resolve("com/nuviolinux/app/features/tmdb/TmdbConfig.kt").delete()

        outDir.resolve("com/nuviolinux/app/features/trakt").apply {
            mkdirs()
            resolve("TraktConfig.kt").writeText(
                """
                |package com.nuviolinux.app.features.trakt
                |
                |object TraktConfig {
                |    const val CLIENT_ID = "${props.getProperty("TRAKT_CLIENT_ID", "")}" 
                |    const val CLIENT_SECRET = "${props.getProperty("TRAKT_CLIENT_SECRET", "")}" 
                |    const val REDIRECT_URI = "${props.getProperty("TRAKT_REDIRECT_URI", "nuvio://auth/trakt")}" 
                |}
                """.trimMargin()
            )
        }

        outDir.resolve("com/nuviolinux/app/features/player/skip").apply {
            mkdirs()
            resolve("IntroDbConfig.kt").writeText(
                """
                |package com.nuviolinux.app.features.player.skip
                |
                |object IntroDbConfig {
                |    const val URL = "${props.getProperty("INTRODB_API_URL", "")}" 
                |}
                """.trimMargin()
            )
        }

        outDir.resolve("com/nuviolinux/app/features/simkl").apply {
            mkdirs()
            resolve("SimklConfig.kt").writeText(
                """
                |package com.nuviolinux.app.features.simkl
                |
                |object SimklConfig {
                |    const val CLIENT_ID = "${props.getProperty("SIMKL_CLIENT_ID", "")}"
                |    const val REDIRECT_URI = "${props.getProperty("SIMKL_REDIRECT_URI", "nuvio://auth/simkl")}"
                |    const val APP_NAME = "${props.getProperty("SIMKL_APP_NAME", "nuvio")}"
                |}
                """.trimMargin()
            )
        }

        outDir.resolve("com/nuviolinux/app/features/details").apply {
            mkdirs()
            resolve("ImdbEpisodeRatingsConfig.kt").writeText(
                """
                |package com.nuviolinux.app.features.details
                |
                |object ImdbEpisodeRatingsConfig {
                |    const val IMDB_RATINGS_API_BASE_URL = "${props.getProperty("IMDB_RATINGS_API_BASE_URL", "")}" 
                |    const val IMDB_TAPFRAME_API_BASE_URL = "${props.getProperty("IMDB_TAPFRAME_API_BASE_URL", "")}" 
                |}
                """.trimMargin()
            )
        }

        outDir.resolve("com/nuviolinux/app/features/debrid").apply {
            mkdirs()
            resolve("PremiumizeConfig.kt").writeText(
                """
                |package com.nuviolinux.app.features.debrid
                |
                |object PremiumizeConfig {
                |    const val CLIENT_ID = "${props.getProperty("PREMIUMIZE_CLIENT_ID", "")}"
                |}
                """.trimMargin()
            )
        }

        outDir.resolve("com/nuviolinux/app/core/build").apply {
            mkdirs()
            resolve("AppVersionConfig.kt").writeText(
                """
                |package com.nuviolinux.app.core.build
                |
                |object AppVersionConfig {
                |    const val VERSION_NAME = "${appVersionName.get()}"
                |    const val VERSION_CODE = ${appVersionCode.get()}
                |    const val DESKTOP_VERSION_NAME = "${desktopAppVersionName.get()}"
                |    const val DESKTOP_VERSION_CODE = ${desktopAppVersionCode.get()}
                |}
                """.trimMargin()
            )
        }

        outDir.resolve("com/nuviolinux/app/features/settings").apply {
            mkdirs()
            resolve("CommunityConfig.kt").writeText(
                """
                |package com.nuviolinux.app.features.settings
                |
                |object CommunityConfig {
                |    const val CONTRIBUTIONS_URL = "${props.getProperty("CONTRIBUTIONS_URL", "")}" 
                |    const val DONATIONS_BASE_URL = "${props.getProperty("DONATIONS_BASE_URL", "")}" 
                |    const val DONATIONS_DONATE_URL = "${props.getProperty("DONATIONS_DONATE_URL", "")}" 
                |}
                """.trimMargin()
            )
        }
    }
}

fun jpackageCompatibleVersion(version: String): String {
    val versionCore = version.substringBefore('-').substringBefore('+').trim()
    val parts = versionCore.split('.').filter { it.isNotBlank() }
    require(parts.isNotEmpty() && parts.size <= 3) {
        "Desktop package version must use one to three numeric components: $version"
    }
    val numbers = parts.map { part ->
        part.toIntOrNull() ?: error("Desktop package version component is not numeric: $version")
    }.toMutableList()
    require(numbers.all { it >= 0 }) {
        "Desktop package version components must not be negative: $version"
    }
    while (numbers.size < 3) {
        numbers += 0
    }
    numbers[0] = numbers[0].coerceAtLeast(1)
    return numbers.joinToString(".")
}

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinxSerialization)
}

val supabaseProps = Properties().apply {
    val propsFile = rootProject.file("local.properties")
    if (propsFile.exists()) propsFile.inputStream().use { load(it) }
}

fun localOrEnvProperty(name: String): String? =
    (
        providers.gradleProperty(name).orNull
            ?: System.getenv(name)
            ?: supabaseProps.getProperty(name)
        )
        ?.trim()
        ?.takeIf { it.isNotBlank() }

val desktopVersionConfigFile = rootProject.file("composeApp/Configuration/DesktopVersion.properties")
val desktopVersionProps = Properties().apply {
    if (desktopVersionConfigFile.exists()) {
        desktopVersionConfigFile.inputStream().use { load(it) }
    }
}
val desktopReleaseVersionName = (
    providers.gradleProperty("nuvio.desktop.versionName").orNull
        ?: System.getenv("NUVIO_DESKTOP_VERSION_NAME")
        ?: supabaseProps.getProperty("NUVIO_DESKTOP_VERSION_NAME")
        ?: desktopVersionProps.getProperty("VERSION_NAME")
        ?: "0.1.0"
    ).trim()
require(desktopReleaseVersionName.isNotBlank()) {
    "Desktop version name must not be blank."
}
val desktopReleaseVersionCode = (
    providers.gradleProperty("nuvio.desktop.versionCode").orNull
        ?: System.getenv("NUVIO_DESKTOP_VERSION_CODE")
        ?: supabaseProps.getProperty("NUVIO_DESKTOP_VERSION_CODE")
        ?: desktopVersionProps.getProperty("VERSION_CODE")
    )?.trim()
    ?.takeIf { it.isNotBlank() }
    ?.toIntOrNull()
    ?: 1
val releaseAppVersionName = desktopReleaseVersionName
val releaseAppVersionCode = desktopReleaseVersionCode
val desktopReleasePackageVersion = jpackageCompatibleVersion(desktopReleaseVersionName)
// Native packages carry the app version itself so all artifacts share one
// number. RPM forbids hyphens in the version component (0.1.15-alpha ->
// 0.1.15alpha); DEB accepts the raw form (0.1.15-alpha).
val desktopRpmReleaseVersion = desktopReleaseVersionName.replace("-", "")
// Package-only release counter shared by every format (Arch pkgrel, RPM
// Release, and the DEB/AppImage/Flatpak filenames). Bumped for rebuilds of
// the same app version; set via NUVIO_PACKAGE_RELEASE or
// nuvio.desktop.packageRelease.
val desktopPackageRelease = (
    providers.gradleProperty("nuvio.desktop.packageRelease").orNull
        ?: System.getenv("NUVIO_PACKAGE_RELEASE")
    )?.trim()
    ?.takeIf { it.isNotBlank() }
    ?.toIntOrNull()
    ?: 1
val generatedRuntimeConfigDir = layout.buildDirectory.dir("generated/runtime-config/kotlin")
val requestedGradleTasks = gradle.startParameter.taskNames.map { taskName ->
    taskName.substringAfterLast(':').lowercase()
}
val runtimeLocalProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) {
        file.inputStream().use(::load)
    }
}

fun runtimeConfigValue(key: String, fallback: String = ""): String =
    runtimeLocalProperties.getProperty(key)?.trim()?.takeIf { it.isNotBlank() }
        ?: providers.environmentVariable(key).orNull?.trim()?.takeIf { it.isNotBlank() }
        ?: fallback

fun runtimeConfigBoolean(key: String, default: Boolean): Boolean =
    when (runtimeConfigValue(key).lowercase()) {
        "1", "true", "yes", "y", "on" -> true
        "0", "false", "no", "n", "off" -> false
        else -> default
    }

val generateRuntimeConfigs = tasks.register<GenerateRuntimeConfigsTask>("generateRuntimeConfigs") {
    outputDir.set(generatedRuntimeConfigDir)
    localPropertiesFile.set(rootProject.layout.projectDirectory.file("local.properties"))
    appVersionName.set(releaseAppVersionName)
    appVersionCode.set(releaseAppVersionCode)
    desktopAppVersionName.set(desktopReleaseVersionName)
    desktopAppVersionCode.set(desktopReleaseVersionCode)
    supabaseUrl.set(runtimeConfigValue("NUVIO_SUPABASE_URL"))
    supabaseAnonKey.set(runtimeConfigValue("NUVIO_SUPABASE_ANON_KEY"))
    supabaseFallbackUrl.set(runtimeConfigValue("NUVIO_SUPABASE_FALLBACK_URL"))
    sentryDsn.set(runtimeConfigValue("SENTRY_DSN"))
    sentryEnvironment.set(
        when {
            requestedGradleTasks.any { "benchmark" in it } -> "benchmark"
            requestedGradleTasks.any { "debug" in it } -> "debug"
            else -> "production"
        }
    )
}

val isLinuxHost = System.getProperty("os.name").contains("linux", ignoreCase = true)
val linuxPlayerBridgeSource = layout.projectDirectory.file("src/desktopMain/native/linux/player_bridge.cpp")
val linuxPlayerBridgeOutput = layout.buildDirectory.file("native/linux/libplayer_bridge.so")
val linuxPlayerBridgeJavaHome = providers.systemProperty("java.home").get()
val buildLinuxPlayerBridge = tasks.register<Exec>("buildLinuxPlayerBridge") {
    notCompatibleWithConfigurationCache("Builds a host-local player bridge for Linux.")
    enabled = isLinuxHost
    inputs.file(linuxPlayerBridgeSource)
    outputs.file(linuxPlayerBridgeOutput)
    commandLine(
        "g++",
        "-std=c++17",
        "-fPIC",
        "-shared",
        "-o", linuxPlayerBridgeOutput.get().asFile.absolutePath,
        linuxPlayerBridgeSource.asFile.absolutePath,
        "-I$linuxPlayerBridgeJavaHome/include",
        "-I$linuxPlayerBridgeJavaHome/include/linux",
        "-ldl",
        "-lpthread",
    )
}

tasks.withType<Jar>().configureEach {
    if (isLinuxHost && name == "desktopJar") {
        dependsOn(buildLinuxPlayerBridge)
        from(linuxPlayerBridgeOutput) { into("native/linux") }
    }
}

if (isLinuxHost) {
    val desktopNativePlayerTasks = setOf(
        "run",
        "runRelease",
        "desktopRun",
        "runDistributable",
        "runReleaseDistributable",
        "desktopRunHot",
        "hotRunDesktop",
        "hotRunDesktopAsync",
        "hotDevDesktop",
        "hotDevDesktopAsync",
        "createDistributable",
        "createReleaseDistributable",
        "createRuntimeImage",
        "package",
        "packageDistributionForCurrentOS",
        "packageDeb",
        "packageReleaseDeb",
        "packageUberJarForCurrentOS",
        "packageReleaseDistributionForCurrentOS",
        "packageReleaseUberJarForCurrentOS",
    )
    tasks.matching { it.name in desktopNativePlayerTasks }.configureEach {
        dependsOn(buildLinuxPlayerBridge)
    }
}

if (isLinuxHost) {
    val desktopAssetsDir = rootProject.file("dist/desktop")
    val appImageDir = layout.buildDirectory.dir("compose/binaries/main-release/app/nuvio-linux")

    fun assembleDesktopPayload(stagingRoot: File) {
        val appImage = appImageDir.get().asFile
        val payloadOpt = stagingRoot.resolve("opt/nuvio-linux")
        payloadOpt.mkdirs()
        appImage.copyRecursively(payloadOpt)
        // copyRecursively resets file permissions to the default (0644), which
        // would strip the executable bit from the launcher and JRE binaries
        // (issue #11: RPM/deb installed the launcher 0744 — owner-exec only, so
        // non-root users could not start the app). Re-apply executable bits
        // from the source app image.
        appImage.walkTopDown().forEach { src ->
            if (src.isFile && src.canExecute()) {
                payloadOpt.resolve(src.relativeTo(appImage).path).setExecutable(true, false)
            }
        }
        val payloadUsr = stagingRoot.resolve("usr")
        val applicationsDir = payloadUsr.resolve("share/applications")
        val metainfoDir = payloadUsr.resolve("share/metainfo")
        val iconsDir = payloadUsr.resolve("share/icons/hicolor")
        applicationsDir.mkdirs()
        metainfoDir.mkdirs()
        desktopAssetsDir.resolve("nuvio-linux.desktop").copyTo(applicationsDir.resolve("nuvio-linux.desktop"))
        desktopAssetsDir.resolve("io.github.jjdizz1l.NuvioLinux.metainfo.xml").copyTo(metainfoDir.resolve("io.github.jjdizz1l.NuvioLinux.metainfo.xml"))
        desktopAssetsDir.resolve("icons/hicolor").copyRecursively(iconsDir)
    }

    val rpmOutputDir = layout.buildDirectory.dir("compose/binaries/main-release/rpm")
    val packageReleaseRpm = tasks.register<Exec>("packageReleaseRpm") {
        group = "distribution"
        description = "Builds a Fedora RPM (Requires: mpv) with desktop integration."
        notCompatibleWithConfigurationCache("Invokes rpmbuild for RPM packaging.")
        dependsOn("createReleaseDistributable", buildLinuxPlayerBridge)
        inputs.dir(layout.buildDirectory.dir("compose/binaries/main-release/app"))
        inputs.dir(desktopAssetsDir)
        inputs.file(rootProject.file("dist/rpm/nuvio-linux.spec"))
        outputs.dir(rpmOutputDir)
        val stagingDir = layout.buildDirectory.dir("native/rpm-staging").get().asFile
        val topDir = layout.buildDirectory.dir("native/rpmbuild").get().asFile
        val outDir = rpmOutputDir.get().asFile
        doFirst {
            stagingDir.deleteRecursively()
            topDir.deleteRecursively()
            assembleDesktopPayload(stagingDir)
            listOf(
                topDir.resolve("BUILD"),
                topDir.resolve("BUILDROOT"),
                topDir.resolve("RPMS/x86_64"),
                topDir.resolve("SOURCES"),
                topDir.resolve("SPECS"),
                topDir.resolve("SRPMS"),
            ).forEach { it.mkdirs() }
            stagingDir.resolve("opt/nuvio-linux/bin/nuvio-linux").setExecutable(true, false)
            val sourceTar = topDir.resolve("SOURCES/nuvio-linux-app-image.tar.gz")
            runCommand(
                listOf(
                    "tar", "-czf", sourceTar.absolutePath,
                    "-C", stagingDir.absolutePath,
                    "opt", "usr",
                ),
                projectDir,
            )
            outDir.mkdirs()
        }
        commandLine(
            "rpmbuild",
            "-bb",
            "--define", "_topdir ${topDir.absolutePath}",
            "--define", "_sourcedir ${topDir.absolutePath}/SOURCES",
            "--define", "_specdir ${topDir.absolutePath}/SPECS",
            "--define", "_builddir ${topDir.absolutePath}/BUILD",
            "--define", "_buildrootdir ${topDir.absolutePath}/BUILDROOT",
            "--define", "_rpmdir ${topDir.absolutePath}/RPMS",
            "--define", "_srcrpmdir ${topDir.absolutePath}/SRPMS",
             "--define", "appversion ${desktopRpmReleaseVersion}",
             "--define", "apprelease ${desktopPackageRelease}",
            rootProject.file("dist/rpm/nuvio-linux.spec").absolutePath,
        )
        doLast {
            val rpmFile = topDir.resolve("RPMS/x86_64")
                .listFiles { file -> file.name.startsWith("nuvio-linux-") && file.name.endsWith(".rpm") }
                ?.firstOrNull()
                ?: error("Expected RPM was not produced under ${topDir.resolve("RPMS/x86_64")}")
            rpmFile.copyTo(outDir.resolve(rpmFile.name), overwrite = true)
        }
    }

    val debOutputDir = layout.buildDirectory.dir("compose/binaries/main-release/deb")
    val packageReleaseDeb = tasks.register<Exec>("packageReleaseDeb") {
        group = "distribution"
        description = "Builds a Debian .deb (Depends: mpv) with desktop integration."
        notCompatibleWithConfigurationCache("Invokes dpkg-deb for Debian packaging.")
        dependsOn("createReleaseDistributable", buildLinuxPlayerBridge)
        inputs.dir(layout.buildDirectory.dir("compose/binaries/main-release/app"))
        inputs.dir(desktopAssetsDir)
        inputs.dir(rootProject.file("dist/deb"))
        outputs.dir(debOutputDir)
        val stagingDir = layout.buildDirectory.dir("native/deb-staging").get().asFile
        val outDir = debOutputDir.get().asFile
        doFirst {
            stagingDir.deleteRecursively()
            assembleDesktopPayload(stagingDir)
            val controlDir = stagingDir.resolve("DEBIAN")
            controlDir.mkdirs()
            rootProject.file("dist/deb/control.in").readText()
                .replace("__VERSION__", "${desktopReleaseVersionName}-${desktopPackageRelease}")
                .let { controlDir.resolve("control").writeText(it) }
            rootProject.file("dist/deb/postinst").copyTo(controlDir.resolve("postinst"))
            rootProject.file("dist/deb/postrm").copyTo(controlDir.resolve("postrm"))
            controlDir.resolve("postinst").setExecutable(true, false)
            controlDir.resolve("postrm").setExecutable(true, false)
            stagingDir.resolve("opt/nuvio-linux/bin/nuvio-linux").setExecutable(true, false)
            outDir.mkdirs()
        }
        commandLine(
            "dpkg-deb",
            "--build",
            "--root-owner-group",
            stagingDir.absolutePath,
            outDir.resolve("nuvio-linux_${desktopReleaseVersionName}-${desktopPackageRelease}_amd64.deb").absolutePath,
        )
    }
}

fun runCommand(command: List<String>, workingDir: File) {
    val process = ProcessBuilder(command)
        .directory(workingDir)
        .redirectErrorStream(true)
        .start()
    val output = process.inputStream.bufferedReader().readText()
    val exitCode = process.waitFor()
    if (exitCode != 0) {
        error("Command failed (exit $exitCode): ${command.joinToString(" ")}\n$output")
    }
}

tasks.withType<KotlinCompilationTask<*>>().configureEach {
    dependsOn(generateRuntimeConfigs)
}

kotlin {
    jvm("desktop") {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

    sourceSets {
        val commonMain by getting {
            kotlin.srcDir(generatedRuntimeConfigDir)
        }
        val desktopMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(libs.kotlinx.coroutines.swing)
                implementation(libs.ktor.client.cio)
                implementation("com.squareup.okhttp3:okhttp:4.12.0")
                implementation(libs.quickjs.kt)
                implementation(libs.ksoup)
            }
        }
        commonMain.dependencies {
            implementation("io.coil-kt.coil3:coil-compose:${libs.versions.coil.get()}") {
                exclude(group = "org.jetbrains.skiko", module = "skiko")
            }
            implementation("io.coil-kt.coil3:coil-network-ktor3:${libs.versions.coil.get()}") {
                exclude(group = "org.jetbrains.skiko", module = "skiko")
            }
            implementation("io.coil-kt.coil3:coil-network-cache-control:${libs.versions.coil.get()}") {
                exclude(group = "org.jetbrains.skiko", module = "skiko")
            }
            implementation("io.coil-kt.coil3:coil-svg:${libs.versions.coil.get()}") {
                exclude(group = "org.jetbrains.skiko", module = "skiko")
            }
            implementation("dev.chrisbanes.haze:haze:1.7.2")
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(compose.materialIconsExtended)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.atomicfu)
            implementation(libs.kmpalette.core)
            implementation(libs.androidx.navigation3.ui)
            implementation(libs.kermit)
            implementation(libs.supabase.postgrest)
            implementation(libs.supabase.auth)
            implementation(libs.supabase.functions)
            implementation(libs.reorderable)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}

compose.desktop {
    application {
        mainClass = "com.nuviolinux.app.MainKt"
        val smokePlayerUrl = providers.gradleProperty("nuvio.desktop.smokePlayerUrl").orNull
            ?: System.getenv("NUVIO_DESKTOP_SMOKE_PLAYER_URL")
        jvmArgs += listOfNotNull(
            "--add-opens=java.desktop/java.awt=ALL-UNNAMED",
            "--add-opens=java.desktop/sun.lwawt=ALL-UNNAMED",
            "--add-opens=java.desktop/sun.awt.X11=ALL-UNNAMED",
            smokePlayerUrl?.takeIf { it.isNotBlank() }?.let { "-Dnuvio.desktop.smokePlayerUrl=$it" },
        )

        nativeDistributions {
            packageName = "nuvio-linux"
            packageVersion = desktopReleasePackageVersion
            vendor = "JJDizz1L"
            modules(
                "java.instrument",
                "java.management",
                "java.net.http",
                "jdk.httpserver",
                "jdk.unsupported",
            )
            linux {
                iconFile.set(project.file("src/desktopMain/resources/icons/nuvio-app-icon.png"))
                rpmLicenseType = "Commercial"
                rpmPackageVersion = desktopReleasePackageVersion
            }
        }

        buildTypes.release.proguard {
            isEnabled.set(false)
        }
    }
}

