pluginManagement {
    includeBuild("../bandwidth-timeout-checker")
}

includeBuild("../bandwidth-timeout-checker") {
    dependencySubstitution {
        substitute(module("io.github.loinguyen.bandwidth:compiler-plugin")).using(project(":compiler-plugin"))
        substitute(module("io.github.loinguyen.bandwidth:plugin-annotations")).using(project(":plugin-annotations"))
    }
}

rootProject.name = "BeatMaps"

if (File("../beatsaver-common-mp").exists()) {
    includeBuild("../beatsaver-common-mp") {
        dependencySubstitution {
            substitute(module("io.beatmaps:BeatMaps-CommonMP")).using(project(":"))
        }
    }
}

file("modules")
    .listFiles()!!
    .filter(File::isDirectory)
    .forEach { directory ->
        val name = directory.name

        include(name)

        project(":$name").apply {
            this.projectDir = directory
        }
    }
