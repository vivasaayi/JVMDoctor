plugins {
    id("java")
}

dependencies {
    implementation("io.prometheus:simpleclient:0.17.0")
    implementation("io.prometheus:simpleclient_hotspot:0.17.0")
    implementation("io.prometheus:simpleclient_httpserver:0.17.0")
}

java {
    withJavadocJar()
    withSourcesJar()
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

jar {
    manifest {
        attributes(
            "Premain-Class" to "com.jvmdoctor.Agent"
        )
    }
}
