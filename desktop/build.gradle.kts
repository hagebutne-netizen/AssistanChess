// Windows-PC-Version: gleiche Erkennung (chesscore) + Stockfish, Oberfläche mit Swing.
plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    implementation(project(":chesscore"))
    implementation("net.java.dev.jna:jna-platform:5.15.0")   // Windows: Klicks durchlassen, aus Aufnahme ausnehmen
    implementation("com.formdev:flatlaf:3.5.4")              // modernes dunkles Design
    testImplementation("junit:junit:4.13.2")
}

application { mainClass.set("de.schachassistent.desktop.MainKt") }

/** Eine einzige JAR mit allem drin – wird vom Windows-Starter (SchachAssistent.exe) gestartet. */
tasks.register<Jar>("fatJar") {
    archiveFileName.set("SchachAssistent.jar")
    manifest { attributes["Main-Class"] = "de.schachassistent.desktop.MainKt" }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(sourceSets.main.get().output)
    dependsOn(configurations.runtimeClasspath)
    from({ configurations.runtimeClasspath.get().filter { it.name.endsWith("jar") }.map { zipTree(it) } })
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/versions/9/module-info.class")
}
