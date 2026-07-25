pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.9.0"
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "prompt-engine"

// modules/*, plugins/*, tests/* を走査し、build.gradle.kts を持つディレクトリのみを
// Gradle サブプロジェクトとして登録する。P0時点では modules/* のみが対象。
// plugins/*（標準Plugin）・tests/*（integration/contract/prompt-regression）は
// 該当フェーズ（P3以降）でプロジェクトが追加された時点で自動的に対象となる。
fun includeProjectsUnder(groupDirName: String) {
    val groupDir = File(rootDir, groupDirName)
    if (!groupDir.exists()) return
    groupDir.listFiles { file -> file.isDirectory }
        ?.filter { File(it, "build.gradle.kts").exists() || File(it, "build.gradle").exists() }
        ?.sortedBy { it.name }
        ?.forEach { dir ->
            val projectPath = ":$groupDirName:${dir.name}"
            include(projectPath)
            project(projectPath).projectDir = dir
        }
}

includeProjectsUnder("modules")
includeProjectsUnder("plugins")
includeProjectsUnder("tests")
