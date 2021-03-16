package ru.ileasile.kotlin

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.register
import org.gradle.process.ExecResult
import org.gradle.process.ExecSpec
import org.jetbrains.dokka.gradle.DokkaMultiModuleTask
import org.jetbrains.dokka.gradle.DokkaPlugin
import java.io.OutputStream

class DocGradlePlugin: Plugin<Project> {
    override fun apply(target: Project): Unit = with(target) {
        pluginManager.run {
            apply(DokkaPlugin::class.java)
        }

        val dokkaTask = tasks.named<DokkaMultiModuleTask>("dokkaHtmlMultiModule").get()
        val dokkaOutput = dokkaTask.outputDirectory.get()
        val docRepoDir = buildDir.resolve("docRepo").absoluteFile
        docRepoDir.deleteRecursively()

        fun execGit(vararg args: String, configure: ExecSpec.() -> Unit = {}): ExecResult {
            return exec {
                this.executable = "git"
                this.args = args.asList()
                this.workingDir = docRepoDir

                configure()
            }
        }

        tasks.register<PublishDocsTask>("publishDocs") {
            group = "publishing"
            dependsOn(dokkaTask)

            doLast {
                val repoUrl = docsRepoUrl.get()
                val branchName = this@register.branchName.get()

                docRepoDir.mkdirs()
                execGit("init")
                execGit("config", "user.email", email.get())
                execGit("config", "user.name", username.get())
                execGit("pull", repoUrl, branchName)

                val copyDestDir = docRepoDir.resolve("docs")
                copyDestDir.deleteRecursively()
                copy {
                    from(dokkaOutput)
                    into(copyDestDir)
                }

                execGit("add", ".")
                val commitResult = execGit("commit", "-m", "[AUTO] Update docs: $version") {
                    isIgnoreExitValue = true
                }
                if (commitResult.exitValue == 0) {
                    execGit("push", "-u", repoUrl, branchName) {
                        this.standardOutput = object: OutputStream() {
                            override fun write(b: Int) { }
                        }
                    }
                }
            }
        }
    }
}
