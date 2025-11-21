plugins {
    id("application")
}

application {
    mainClass.set("com.jvmdoctor.SampleApp")
}

dependencies {
}

java {
    withJavadocJar()
    withSourcesJar()
}
