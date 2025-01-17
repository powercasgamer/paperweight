plugins {
    `config-kotlin`
    `config-publish`
}

dependencies {
    shade(projects.paperweightLib)

    implementation(libs.kotson)
}

gradlePlugin {
    plugins.all {
        description = "Gradle plugin for developing Paper"
        implementationClass = "io.papermc.paperweight.core.PaperweightCore"
    }
}
