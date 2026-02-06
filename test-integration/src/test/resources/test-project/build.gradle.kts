plugins {
    kotlin("jvm") version "2.2.21"
    application
}

repositories {
    mavenCentral()
}

application {
    mainClass.set("com.jonnyzzz.mcpSteroid.demo.DemoByJonnyzzz" + "Kt")
}

kotlin {
    jvmToolchain(21)
}
