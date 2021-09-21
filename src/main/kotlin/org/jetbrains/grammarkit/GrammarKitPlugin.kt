package org.jetbrains.grammarkit

import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.plugins.PluginInstantiationException
import org.jetbrains.grammarkit.tasks.GenerateLexerTask
import org.jetbrains.grammarkit.tasks.GenerateParserTask
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

@Suppress("unused")
open class GrammarKitPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        checkGradleVersion(project)

        val extension = project.extensions.create(
            GrammarKitConstants.GROUP_NAME,
            GrammarKitPluginExtension::class.java,
        )

        extension.grammarKitRelease.convention(GrammarKitConstants.LATEST_VERSION)
        extension.jflexRelease.convention(GrammarKitConstants.JFLEX_DEFAULT_VERSION)

        val grammarKitClassPathConfiguration =
            project.configurations.create(GrammarKitConstants.GRAMMAR_KIT_CLASS_PATH_CONFIGURATION_NAME)
        val compileClasspathConfiguration = project.configurations.getByName(JavaPlugin.COMPILE_CLASSPATH_CONFIGURATION_NAME)
        val compileOnlyConfiguration = project.configurations.getByName(JavaPlugin.COMPILE_ONLY_CONFIGURATION_NAME)
        val bomConfiguration = project.configurations.maybeCreate(GrammarKitConstants.BOM_CONFIGURATION_NAME)

        project.tasks.register(GrammarKitConstants.GENERATE_LEXER_TASK_NAME, GenerateLexerTask::class.java) { task ->
            task.description = "Generates lexers for IntelliJ-based plugin"
            task.group = GrammarKitConstants.GROUP_NAME
        }

        project.tasks.withType(GenerateLexerTask::class.java).configureEach { task ->
            task.classpath.setFrom(project.provider {
                getClasspath(grammarKitClassPathConfiguration, compileClasspathConfiguration) { file ->
                    file.name.startsWith("jflex")
                }
            })

            task.doFirst {
                if (task.purgeOldFiles.orNull == true) {
                    task.targetFile.get().asFile.deleteRecursively()
                }
            }
        }

        project.tasks.register(GrammarKitConstants.GENERATE_PARSER_TASK_NAME, GenerateParserTask::class.java) { task ->
            task.description = "Generates parsers for IntelliJ-based plugin"
            task.group = GrammarKitConstants.GROUP_NAME
        }

        project.tasks.withType(GenerateParserTask::class.java).configureEach { task ->
            val requiredLibs = listOf(
                "jdom", "trove4j", "junit", "guava", "asm-all", "automaton", "platform-api", "platform-impl",
                "util", "annotations", "picocontainer", "extensions", "idea", "openapi", "Grammar-Kit",
                "platform-util-ui", "platform-concurrency", "intellij-deps-fastutil",
                // CLion unlike IDEA contains `MockProjectEx` in `testFramework.jar` instead of `idea.jar`
                // so this jar should be in `requiredLibs` list to avoid `NoClassDefFoundError` exception
                // while parser generation with CLion distribution
                "testFramework", "3rd-party"
            )

            task.classpath.setFrom(project.provider {
                getClasspath(grammarKitClassPathConfiguration, compileClasspathConfiguration) { file ->
                    requiredLibs.any {
                        file.name.equals("$it.jar", true) || file.name.startsWith("$it-")
                    }
                }
            })

            task.doFirst {
                if (task.purgeOldFiles.orNull == true) {
                    task.targetRootOutputDir.get().asFile.apply {
                        resolve(task.pathToParser.get()).deleteRecursively()
                        resolve(task.pathToPsiRoot.get()).deleteRecursively()
                    }
                }
            }
        }

        project.repositories.apply {
            maven {
                it.url = URI("https://cache-redirector.jetbrains.com/intellij-dependencies")
            }
            maven {
                it.url = URI("https://cache-redirector.jetbrains.com/intellij-repository/releases")
            }
            ivy {
                it.url = URI("https://github.com/JetBrains/Grammar-Kit/releases/download")
                it.patternLayout { layout -> layout.artifact("[revision]/grammar-kit-[revision].zip") }
                it.metadataSources { metadata -> metadata.artifact() }
            }
        }

        project.afterEvaluate {
            val grammarKitRelease = getGrammarKitReleaseVersion(extension)
            val jflexRelease = extension.jflexRelease.get()
            val intellijRelease = extension.intellijRelease.orNull

            if (intellijRelease == null) {
                compileOnlyConfiguration.apply {
                    dependencies.addAll(listOf(
                        "com.github.JetBrains:Grammar-Kit:$grammarKitRelease",
                        "org.jetbrains.intellij.deps.jflex:jflex:$jflexRelease",
                    ).map(project.dependencies::create))

                    exclude(mapOf(
                        "group" to "org.jetbrains.plugins",
                        "module" to "ant",
                    ))
                    exclude(mapOf(
                        "group" to "org.jetbrains.plugins",
                        "module" to "idea",
                    ))
                }
                bomConfiguration.apply {
                    dependencies.addAll(listOf(
                        "dev.thiagosouto:plugin:1.3.4",
                    ).map(project.dependencies::create))

                    exclude(mapOf(
                        "group" to "soutoPackage",
                        "module" to "test1",
                    ))
                }
            } else {
                configureGrammarKitClassPath(project, grammarKitClassPathConfiguration, grammarKitRelease, jflexRelease, intellijRelease)
            }
        }
    }

    private fun getGrammarKitReleaseVersion(extension: GrammarKitPluginExtension) =
        extension.grammarKitRelease.get().takeIf { it != GrammarKitConstants.LATEST_VERSION } ?: run {
            try {
                return URL(GrammarKitConstants.GRAMMAR_KIT_LATEST_RELEASE_URL).openConnection().run {
                    (this as HttpURLConnection).instanceFollowRedirects = false
                    getHeaderField("Location").split('/').last()
                }
            } catch (e: IOException) {
                throw GradleException("Cannot resolve the latest GrammarKit version")
            }
        }

    private fun configureGrammarKitClassPath(
        project: Project,
        grammarKitClassPathConfiguration: Configuration,
        grammarKitRelease: String,
        jflexRelease: String,
        intellijRelease: String,
    ) {
        grammarKitClassPathConfiguration.apply {
            dependencies.addAll(listOf(
                "com.github.JetBrains:Grammar-Kit:$grammarKitRelease",
                "org.jetbrains.intellij.deps.jflex:jflex:$jflexRelease",
                "com.jetbrains.intellij.platform:indexing-impl:$intellijRelease",
                "com.jetbrains.intellij.platform:analysis-impl:$intellijRelease",
                "com.jetbrains.intellij.platform:core-impl:$intellijRelease",
                "com.jetbrains.intellij.platform:lang-impl:$intellijRelease",
                "org.jetbrains.intellij.deps:asm-all:7.0.1",
            ).map(project.dependencies::create))

            exclude(mapOf("group" to "com.jetbrains.rd"))
            exclude(mapOf("group" to "org.jetbrains.marketplace"))
            exclude(mapOf("group" to "org.roaringbitmap"))
            exclude(mapOf("group" to "org.jetbrains.plugins"))
            exclude(mapOf("module" to "idea"))
            exclude(mapOf("module" to "ant"))
        }
    }

    private fun getClasspath(
        grammarKitClassPathConfiguration: Configuration,
        compileClasspathConfiguration: Configuration,
        filter: (File) -> Boolean,
    ) = when {
        !grammarKitClassPathConfiguration.isEmpty -> grammarKitClassPathConfiguration.files
        else -> compileClasspathConfiguration.files.filter(filter)
    }

    private fun checkGradleVersion(project: Project) {
        if (Version.parse(project.gradle.gradleVersion) < Version.parse("6.6")) {
            throw PluginInstantiationException("gradle-grammarkit-plugin requires Gradle 6.6 and higher")
        }
    }
}
