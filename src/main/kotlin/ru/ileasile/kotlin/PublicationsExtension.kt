package ru.ileasile.kotlin

import groovy.lang.Closure
import io.github.gradlenexus.publishplugin.NexusPublishExtension
import io.github.gradlenexus.publishplugin.NexusPublishPlugin
import org.gradle.api.Action
import org.gradle.api.Project
import org.gradle.api.artifacts.dsl.RepositoryHandler
import org.gradle.api.artifacts.repositories.MavenArtifactRepository
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.provider.Property
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPom
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.bundling.Jar
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.get
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.invoke
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.property
import org.gradle.plugins.signing.SigningExtension
import org.jetbrains.dokka.gradle.DokkaTask

class PublicationsExtension(private val project: Project) {
    private var _pomConfigurator: PomConfigurator? = null
    private var _signingCredentials: SigningCredentials? = null
    private var _sonatypeSettings: SonatypeSettings? = null
    private val _repositoryConfigurators = mutableListOf<Action<in RepositoryHandler>>()

    /**
     * Default group that is used for publications if not explicitly set in [ArtifactPublication.groupId]
     */
    val defaultGroup: Property<String> = project.objects.property()

    /**
     * If [ArtifactPublication.artifactId] is not set for a publication, artifact ID is combined
     * from [defaultArtifactIdPrefix] and [ArtifactPublication.publicationName] by concatenation
     */
    val defaultArtifactIdPrefix: Property<String> = project.objects.property()

    /**
     * Tells if this local repositories configuration should be extended by parent projects configurations
     */
    @Suppress("MemberVisibilityCanBePrivate")
    val inheritRepositories: Property<Boolean> = project.objects.property()

    init {
        if (project === project.rootProject) {
            registerAsRootProject()
        }
        project.afterEvaluate {
            bindPublications()
        }
    }

    /**
     * Setup additional configuration of Maven POM file.
     * You can use extensions defined in `pomUtil.kt` file.
     */
    @Suppress("unused")
    fun pom(configurator: PomConfigurator) {
        _pomConfigurator = configurator
    }

    /**
     * Setup additional configuration of Maven POM file.
     * You can use extensions defined in `pomUtil.kt` file.
     */
    @Suppress("unused")
    fun pom(configurator: Closure<in MavenPom>) {
        _pomConfigurator = PomConfigurator { project.configure(this, configurator) }
    }

    /**
     * Returns settings of artifacts signing
     */
    @Suppress("MemberVisibilityCanBePrivate")
    fun signingCredentials(): SigningCredentials? = _signingCredentials

    /**
     * Setup artifacts signing
     */
    @Suppress("unused")
    fun signingCredentials(key: String?, privateKey: String?, keyPassphrase: String?) {
        if (key == null || privateKey == null || keyPassphrase == null) return
        _signingCredentials = SigningCredentials(key, privateKey, keyPassphrase)
    }

    /**
     * Returns settings of publishing to Sonatype
     */
    @Suppress("MemberVisibilityCanBePrivate")
    fun sonatypeSettings(): SonatypeSettings? = _sonatypeSettings

    /**
     * Setup publishing to Sonatype repository
     */
    @Suppress("unused")
    fun sonatypeSettings(
        username: String?,
        password: String?,
        repositoryDescription: String
    ) {
        _sonatypeSettings = SonatypeSettings(username, password, repositoryDescription)
        applyNexusPlugin(_sonatypeSettings!!, defaultGroup.orNull)
    }

    /**
     * Adds a publication for this project
     */
    @Suppress("MemberVisibilityCanBePrivate")
    fun publication(publication: ArtifactPublication) {
        project.afterEvaluate {
            addPublication(publication)
        }
    }

    /**
     * Adds and configures a publication for this project
     */
    @Suppress("unused")
    fun publication(configuration: Action<in ArtifactPublication>) {
        val res = ArtifactPublication(project)
        configuration(res)
        publication(res)
    }

    /**
     * Adds and configures a publication for this project
     */
    @Suppress("unused")
    fun publication(configuration: Closure<in ArtifactPublication>) {
        val res = ArtifactPublication(project)
        project.configure(res, configuration)
        publication(res)
    }

    /**
     * Configure repositories publishing to that will be bound to [PUBLISH_LOCAL_TASK] task
     */
    @Suppress("unused")
    fun localRepositories(configure: Action<in RepositoryHandler>) {
        _repositoryConfigurators.add(configure)
    }

    /**
     * Adds Maven repository with specified [name] and local [path]
     */
    @Suppress("MemberVisibilityCanBePrivate")
    fun RepositoryHandler.localMavenRepository(name: String?, path: Any): MavenArtifactRepository {
        return maven {
            name?.let { this.name = it }
            this.url = project.file(path).toURI()
        }
    }

    /**
     * Adds Maven repository with [path] and default name
     */
    @Suppress("unused")
    fun RepositoryHandler.localMavenRepository(path: Any) = localMavenRepository(null, path)

    /**
     * Adds Maven repository with name "Local".
     * Path of this repository is taken from project's property `localPublicationsRepo` if it is set,
     * and to "$buildDir/artifacts/maven" if it's not set.
     */
    @Suppress("unused")
    fun RepositoryHandler.defaultLocalMavenRepository() = localMavenRepository(
        "Local",
        project.findProperty("localPublicationsRepo")
            ?: project.buildDir.toPath().resolve("artifacts/maven")
    )

    private fun registerAsRootProject() {
        project.tasks.register(PUBLISH_LOCAL_TASK) {
            group = PUBLISHING_GROUP
        }
    }

    private fun applyRepositoriesWithInheritance(repositoryHandler: RepositoryHandler) {
        val extensions = if (inheritRepositories.getOrElse(true)) {
            project.thisWithParents().mapNotNull { it.getPublicationsExtension() }.toList()
        } else listOf(this)

        extensions.flatMap { it._repositoryConfigurators }.forEach {
            it.execute(repositoryHandler)
        }
    }

    private fun bindPublications() {
        if (!project.rootProject.hasCorrectRootProject()) return

        var repositoryNames = setOf<String>()

        project.extensions.configure<PublishingExtension> {
            repositoryNames = repositories.names.toSet()
            repositories {
                applyRepositoriesWithInheritance(this)
            }
            repositoryNames = repositories.names - repositoryNames
        }

        val thisProject = project

        project.rootProject.tasks {
            named(PUBLISH_LOCAL_TASK) {
                repositoryNames.forEach { repoName ->
                    dependsOn(thisProject.tasks[getPublishTaskName(repoName)])
                }
            }
        }
    }

    private fun addPublication(publication: ArtifactPublication) {
        if (!project.rootProject.hasCorrectRootProject()) return

        val publicationName = publication.publicationName.get()

        project.extensions.configure<JavaPluginExtension> {
            withSourcesJar()
            withJavadocJar()
        }

        project.tasks {
            named<DokkaTask>(DOKKA_HTML_TASK) {
                outputDirectory.set(project.buildDir.resolve("dokkaHtml"))
            }

            val javadocDestDir = project.buildDir.resolve("dokkaJavadoc")

            val dokkaJavadoc = named<DokkaTask>(DOKKA_JAVADOC_TASK) {
                outputDirectory.set(javadocDestDir)
            }

            named<Jar>(JAVADOC_JAR_TASK) {
                dependsOn(dokkaJavadoc)
                from(javadocDestDir)
            }
        }

        project.extensions.configure<PublishingExtension> {
            publications {
                register(publicationName, MavenPublication::class.java) {
                    artifactId = publication.artifactId.get()
                    groupId = publication.groupId.get()

                    from(project.components["java"])

                    pom {
                        name.set(publication.packageName)
                        description.set(publication.description)

                        applyPomConfigurators(this)
                    }
                }
            }
        }

        val thisProject = project

        project.rootProject.tasks {
            if (publication.publishToSonatype.get() && thisProject.getClosestProperty { sonatypeSettings() } != null) {
                named(PUBLISH_TO_SONATYPE_WITH_EXCLUDING_TASK) {
                    dependsOn(thisProject.tasks[getPublishTaskName(SONATYPE_REPOSITORY_NAME, publicationName)])
                }
            }
        }

        project.getClosestProperty { signingCredentials() }?.apply {
            project.extensions.configure<SigningExtension> {
                sign(project.extensions.getByType<PublishingExtension>().publications[publicationName])

                @Suppress("UnstableApiUsage")
                useInMemoryPgpKeys(key, privateKey, keyPassphrase)
            }
        }
    }

    private fun applyPomConfigurators(pom: MavenPom): MavenPom {
        return project.thisWithParents().mapNotNull {
            it.getPublicationsExtension()?._pomConfigurator
        }.toList().foldRight(pom) { configurator, acc ->
            configurator(acc)
            acc
        }
    }

    private fun applyNexusPlugin(settings: SonatypeSettings, packageGroup: String?) {
        project.pluginManager.run {
            apply(NexusPublishPlugin::class.java)
        }

        project.extensions.configure<NexusPublishExtension>("nexusPublishing") {
            packageGroup?.let { this.packageGroup.set(it) }
            repositoryDescription.set(settings.repositoryDescription)

            repositories {
                sonatype {
                    username.set(settings.username)
                    password.set(settings.password)
                }
            }
        }

        project.afterEvaluate {
            configureNexusPublishingTasks()
        }
    }

    private fun configureNexusPublishingTasks() {
        project.tasks.register(PUBLISH_TO_SONATYPE_WITH_EXCLUDING_TASK) {
            group = PUBLISHING_GROUP
        }

        project.tasks.register(PUBLISH_TO_SONATYPE_AND_RELEASE_TASK) {
            group = PUBLISHING_GROUP

            dependsOn(PUBLISH_TO_SONATYPE_WITH_EXCLUDING_TASK, CLOSE_AND_RELEASE_TASK)
        }

        project.tasks.named(CLOSE_AND_RELEASE_TASK) {
            mustRunAfter(PUBLISH_TO_SONATYPE_WITH_EXCLUDING_TASK)
        }
    }

    companion object {
        const val NAME = "kotlinPublications"

        const val PUBLISH_LOCAL_TASK = "publishLocal"

        private const val SONATYPE_REPOSITORY_NAME = "Sonatype"
        private const val PUBLISH_TO_SONATYPE_WITH_EXCLUDING_TASK = "publishToSonatypeWithExcluding"
        private const val PUBLISH_TO_SONATYPE_AND_RELEASE_TASK = "publishToSonatypeAndRelease"
        private const val CLOSE_AND_RELEASE_TASK = "closeAndReleaseStagingRepository"

        private const val DOKKA_HTML_TASK = "dokkaHtml"
        private const val DOKKA_JAVADOC_TASK = "dokkaJavadoc"
        private const val JAVADOC_JAR_TASK = "javadocJar"
    }
}
