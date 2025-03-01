plugins {
    id("io.papermc.paperweight.userdev") version "1.3.5"
}

dependencies {
    paperDevBundle("1.18.2-R0.1-SNAPSHOT")

    compileOnly(project(":slimeworldmanager-nms-common"))
    compileOnly(project(":slimeworldmanager-api"))
    compileOnly(project(":slimeworldmanager-classmodifierapi"))

    implementation("com.flowpowered:flow-nbt:2.0.2")
}


tasks {
    reobfJar {
        outputJar.set(layout.buildDirectory.file("libs/${project.name}-${project.version}.jar"))
    }

    generateMetadataFileForMavenPublication {
        dependsOn(reobfJar)
    }

    java {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(17))
        }
    }

    compileJava {
        options.release.set(17)
    }

}

description = "slimeworldmanager-nms-v118-2"
