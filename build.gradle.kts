plugins {
    `java-gradle-plugin`
    id("com.gradle.plugin-publish") version "0.21.0"
    kotlin("libs.publisher") version "0.0.61-dev-33"
    id("org.jlleitschuh.gradle.ktlint") version "10.0.0"
    `kotlin-dsl`
}

group = "org.jetbrains.kotlin"
version = detectVersion()

fun detectVersion(): String {
    val buildNumber = rootProject.findProperty("build.number") as String?
    return if (buildNumber != null) {
        if (hasProperty("build.number.detection")) {
            "$version-dev-$buildNumber"
        } else {
            buildNumber
        }
    } else if (hasProperty("release")) {
        version as String
    } else {
        "$version-dev"
    }
}

val detectVersionForTC by tasks.registering {
    doLast {
        println("##teamcity[buildNumber '$version']")
    }
}

val junitVersion: String by project

repositories {
    mavenCentral()
    gradlePluginPortal()
}

dependencies {
    implementation("org.jetbrains.dokka:dokka-gradle-plugin:1.8.10")
    implementation("io.github.gradle-nexus:publish-plugin:1.1.0")
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:1.8.10")

    // For maven-publish
    implementation(gradleApi())

    // Test dependencies: kotlin-test and Junit 5
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter-api:$junitVersion")
    testImplementation("io.kotlintest:kotlintest-assertions:3.4.2")
    testImplementation(gradleTestKit())

    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:$junitVersion")
}

java {
    withSourcesJar()
    withJavadocJar()

    toolchain {
        languageVersion.set(JavaLanguageVersion.of(8))
    }
}

tasks.withType<JavaCompile> {
    sourceCompatibility = JavaVersion.VERSION_1_8.toString()
    targetCompatibility = JavaVersion.VERSION_1_8.toString()
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
    outputs.upToDateWhen { false }
}

tasks.runKtlintCheckOverMainSourceSet {
    dependsOn(tasks.runKtlintCheckOverKotlinScripts)
}

tasks.runKtlintFormatOverMainSourceSet {
    dependsOn(tasks.runKtlintFormatOverKotlinScripts)
}

tasks.runKtlintFormatOverTestSourceSet {
    dependsOn(tasks.runKtlintFormatOverKotlinScripts)
}

val publishingPlugin = "publishing"
val docPlugin = "doc"

@Suppress("UnstableApiUsage")
gradlePlugin {
    website.set("https://github.com/Kotlin/kotlin-libs-publisher")
    vcsUrl.set(website.get())
    plugins {
        create(publishingPlugin) {
            id = "org.jetbrains.kotlin.libs.publisher"
            implementationClass = "org.jetbrains.kotlinx.publisher.ApiPublishGradlePlugin"
            displayName = "Kotlin libs publisher plugin"
            description = displayName
            tags.set(listOf("kotlin", "publishing"))
        }
        create(docPlugin) {
            id = "org.jetbrains.kotlin.libs.doc"
            implementationClass = "org.jetbrains.kotlinx.publisher.DocGradlePlugin"
            displayName = "Kotlin libs documenting plugin"
            description = displayName
            tags.set(listOf("kotlin", "documentation"))
        }
    }
}

pluginBundle {
    // These settings are set for the whole plugin bundle
    website = "https://github.com/Kotlin/kotlin-libs-publisher"
    vcsUrl = website
}

kotlinPublications {
    localRepositories {
        defaultLocalMavenRepository()
    }
}

tasks.whenTaskAdded {
    val task = this
    if (task.name == "generateMetadataFileForPluginMavenPublication") {
        task.mustRunAfter(":publishPluginJar", ":publishPluginJavaDocsJar")
    }
}
