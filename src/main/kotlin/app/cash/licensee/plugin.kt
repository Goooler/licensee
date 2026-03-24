/*
 * Copyright (C) 2021 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package app.cash.licensee

import com.android.build.api.variant.AndroidComponentsExtension
import java.util.Locale.ROOT
import javax.inject.Inject
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.initialization.Settings
import org.gradle.api.model.ObjectFactory
import org.gradle.api.plugins.JavaPlugin.RUNTIME_CLASSPATH_CONFIGURATION_NAME
import org.gradle.api.reporting.ReportingExtension
import org.gradle.api.tasks.TaskContainer
import org.gradle.api.tasks.TaskProvider
import org.gradle.language.base.plugins.LifecycleBasePlugin.CHECK_TASK_NAME
import org.gradle.language.base.plugins.LifecycleBasePlugin.VERIFICATION_GROUP
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType.androidJvm
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType.common

private const val BASE_TASK_NAME = "licensee"
private const val REPORT_FOLDER = "licensee"

@Suppress("unused") // Instantiated reflectively by Gradle.
class LicenseePlugin @Inject constructor(private val objects: ObjectFactory) : Plugin<Any> {
  override fun apply(target: Any) {
    when (target) {
      is Project -> target.apply()
      is Settings -> target.apply(objects)
      else -> error("Licensee plugin can only be applied to a Project or Settings.")
    }
  }
}

private fun Project.apply() {
  val extension = objects.newInstance(MutableLicenseeExtension::class.java)
  extensions.add(LicenseeExtension::class.java, "licensee", extension)

  tasks.withType(LicenseeTask::class.java).configureEach {
    it.dependencyConfig.convention(extension.toDependencyTreeConfig())
    it.validationConfig.convention(extension.toLicenseValidationConfig())
    it.violationAction.convention(extension.violationAction)
    it.unusedAction.convention(extension.unusedAction)

    it.outputDir.convention(
      extensions.getByType(ReportingExtension::class.java).baseDirectory.dir(REPORT_FOLDER)
    )
  }

  // Note: java-library applies java so we only need to look for the latter.
  // Note: org.jetbrains.kotlin.jvm applies java so we only need to look for the latter.
  pluginManager.withPlugin("org.gradle.java") {
    // Special case: KMP with JVM withJava():
    // withKotlinMultiPlatformPlugin did already run, so the jvm target is already set, ignore
    // another setup.
    if (!pluginManager.hasPlugin("org.jetbrains.kotlin.multiplatform")) {
      configureJavaPlugin(this)
    }
  }
  pluginManager.withPlugin("org.jetbrains.kotlin.js") {
    // The JS plugin uses the same runtime configuration name as the Java plugin.
    configureJavaPlugin(this)
  }

  withKotlinMultiPlatformPlugin(
    this,
    withAndroid = false,
    extension = extension,
  ) // see android logic below

  pluginManager.withPlugin("com.android.application") { configureAndroidPlugin(this, extension) }
  pluginManager.withPlugin("com.android.library") { configureAndroidPlugin(this, extension) }
  pluginManager.withPlugin("com.android.dynamic-feature") {
    configureAndroidPlugin(this, extension)
  }

  afterEvaluate {
    require(BASE_TASK_NAME in tasks.names) {
      val name =
        if (path == ":") {
          "root project"
        } else {
          "project $path"
        }
      "'app.cash.licensee' requires compatible language/platform plugin to be applied ($name)"
    }
  }
}

private fun Settings.apply(objects: ObjectFactory) {
  val extension = objects.newInstance(MutableLicenseeExtension::class.java)
  extensions.add(LicenseeExtension::class.java, "licensee", extension)

  gradle.lifecycle.beforeProject { project ->
    project.pluginManager.apply(LicenseePlugin::class.java)
    val projectExtension =
      (project.extensions.getByType(LicenseeExtension::class.java) as MutableLicenseeExtension)
        .apply {
        allowedIdentifiers.convention(extension.allowedIdentifiers)
        allowedUrls.convention(extension.allowedUrls)
        allowedDependencies.convention(extension.allowedDependencies)
        ignoredGroupIds.convention(extension.ignoredGroupIds)
        violationAction.convention(extension.violationAction)
        unusedAction.convention(extension.unusedAction)
        bundleAndroidAsset.convention(extension.bundleAndroidAsset)
        androidAssetReportPath.convention(extension.androidAssetReportPath)
      }

    extension.ignoredCoordinates.configureEach { settingsIgnoredCoord ->
      if (settingsIgnoredCoord.name in projectExtension.ignoredCoordinates.names) {
        projectExtension.ignoredCoordinates.named(settingsIgnoredCoord.name) { projectIgnoredCoord
          ->
          projectIgnoredCoord.ignoredDatas.convention(settingsIgnoredCoord.ignoredDatas)
        }
      } else {
        projectExtension.ignoredCoordinates.register(settingsIgnoredCoord.name) {
          projectIgnoredCoord ->
          projectIgnoredCoord.ignoredDatas.convention(settingsIgnoredCoord.ignoredDatas)
        }
      }
    }
  }
}

private fun configureAndroidPlugin(project: Project, extension: MutableLicenseeExtension) {
  val rootTask = registerRootTask(project, "all Android variants")
  configureAndroidVariants(project, rootTask, extension)
  withKotlinMultiPlatformPlugin(project, withAndroid = true, extension)
}

private fun withKotlinMultiPlatformPlugin(
  project: Project,
  withAndroid: Boolean,
  extension: MutableLicenseeExtension,
) {
  project.pluginManager.withPlugin("org.jetbrains.kotlin.multiplatform") {
    val rootTask = registerRootTask(project, "all Kotlin targets")
    configureKotlinMultiplatformTargets(project, rootTask)
    if (withAndroid) {
      configureAndroidVariants(project, rootTask, extension)
    }
  }
}

private fun registerRootTask(project: Project, target: String): TaskProvider<Task> {
  val rootTask =
    if (BASE_TASK_NAME in project.tasks.names) {
      project.tasks.named(BASE_TASK_NAME)
    } else {
      project.tasks.register(BASE_TASK_NAME)
    }

  rootTask.configure {
    it.group = VERIFICATION_GROUP
    it.description = taskDescription(target)
  }
  project.tasks.named(CHECK_TASK_NAME).configure { it.dependsOn(rootTask) }
  return rootTask
}

private fun configureAndroidVariants(
  project: Project,
  rootTask: TaskProvider<Task>,
  extension: MutableLicenseeExtension,
) {
  val androidComponents = project.extensions.getByType(AndroidComponentsExtension::class.java)
  androidComponents.onVariants { variant ->
    val suffix = variant.name.replaceFirstChar { it.titlecase(ROOT) }
    val taskName = "${BASE_TASK_NAME}Android$suffix"

    val task =
      project.tasks.maybeRegister(taskName) {
        it.group = VERIFICATION_GROUP
        it.description = taskDescription("Android ${variant.name} variant")

        it.configurationToCheck(variant.runtimeConfiguration)

        val reportBase =
          project.extensions
            .getByType(ReportingExtension::class.java)
            .baseDirectory
            .dir(REPORT_FOLDER)
        it.outputDir.set(reportBase.map { it.dir("android$suffix") })
      }

    rootTask.configure { it.dependsOn(task) }

    if (extension.bundleAndroidAsset.get()) {
      val capitalizedVariantName = variant.name.replaceFirstChar { it.titlecase(ROOT) }
      val copyArtifactsTask =
        project.tasks.register(
          "copy${capitalizedVariantName}LicenseeReportToAssets",
          AssetCopyTask::class.java,
        ) { asset ->
          asset.inputFile.set(task.flatMap { it.jsonOutput })
          asset.outputFilePath.set(extension.androidAssetReportPath)
        }

      variant.sources.assets!!.addGeneratedSourceDirectory(
        copyArtifactsTask,
        AssetCopyTask::assetDirectory,
      )
    }
  }
}

private fun configureKotlinMultiplatformTargets(project: Project, rootTask: TaskProvider<Task>) {
  val kotlin = project.extensions.getByType(KotlinMultiplatformExtension::class.java)
  val targets = kotlin.targets
  targets.configureEach { target ->
    if (target.platformType == common) {
      return@configureEach // All common dependencies end up in platform targets.
    }
    if (target.platformType == androidJvm) {
      return@configureEach // handled by android logic.
    }

    val suffix = target.name.replaceFirstChar { it.titlecase(ROOT) }
    val task =
      project.tasks.maybeRegister("$BASE_TASK_NAME$suffix") {
        it.group = VERIFICATION_GROUP
        it.description = taskDescription("Kotlin ${target.name} target")

        val compilation = target.compilations.getByName(KotlinCompilation.MAIN_COMPILATION_NAME)
        // Fallback to compile dependencies when runtime isn't supported, e.g. Kotlin/Native.
        val runtimeConfigurationName =
          compilation.runtimeDependencyConfigurationName
            ?: compilation.compileDependencyConfigurationName

        val runtimeConfiguration = project.configurations.named(runtimeConfigurationName)
        it.configurationToCheck(runtimeConfiguration)

        val reportBase =
          project.extensions
            .getByType(ReportingExtension::class.java)
            .baseDirectory
            .dir(REPORT_FOLDER)
        it.outputDir.set(reportBase.map { it.dir(target.name) })
      }

    rootTask.configure { it.dependsOn(task) }
  }
}

private fun configureJavaPlugin(project: Project) {
  val task =
    project.tasks.maybeRegister(BASE_TASK_NAME) {
      it.group = VERIFICATION_GROUP
      it.description = taskDescription()

      val configuration = project.configurations.named(RUNTIME_CLASSPATH_CONFIGURATION_NAME)
      it.configurationToCheck(configuration)
    }
  project.tasks.named(CHECK_TASK_NAME).configure { it.dependsOn(task) }
}

// TODO: https://github.com/gradle/gradle/issues/8057
private fun TaskContainer.maybeRegister(
  name: String,
  config: (LicenseeTask) -> Unit,
): TaskProvider<LicenseeTask> =
  if (name in names) {
    named(name, LicenseeTask::class.java, config)
  } else {
    register(name, LicenseeTask::class.java, config)
  }

private fun taskDescription(target: String? = null) = buildString {
  append("Run Licensee dependency license validation")
  if (target != null) {
    append(" on ")
    append(target)
  }
}
