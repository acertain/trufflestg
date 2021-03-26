import java.util.Properties
import org.apache.tools.ant.filters.ReplaceTokens
import org.jetbrains.dokka.gradle.DokkaTask
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.jetbrains.kotlin.gradle.plugin.KotlinSourceSet
import org.gradle.api.internal.HasConvention

group = project.properties["group"].toString()
version = project.properties["version"].toString()


buildscript {
  repositories {
    mavenCentral()
    maven { url = uri("https://dl.bintray.com/kotlin/kotlin-eap") }
  }
  dependencies {
    classpath("org.jetbrains.dokka:dokka-gradle-plugin:0.10.0")
  }
}

repositories {
  jcenter()
  mavenCentral()
  maven { url = uri("https://jitpack.io") }
  maven { url = uri("http://palantir.bintray.com/releases") }
}

plugins {
  application
  `build-scan`
  idea
  id("org.jetbrains.dokka") version "0.9.17"
  id("org.ajoberstar.git-publish") version "2.1.1"
  kotlin("jvm") version "1.4.30"
  kotlin("kapt") version "1.4.30"
}

val compiler: Configuration by configurations.creating

val graalVersion = "21.0.0"

dependencies {
  implementation(kotlin("stdlib"))
  implementation(kotlin("stdlib-jdk8"))
  arrayOf("asm","asm-tree","asm-commons").forEach { implementation("org.ow2.asm:$it:7.1") }
  implementation("org.fusesource.jansi:jansi:1.18")
  compiler("org.graalvm.compiler:compiler:$graalVersion")
  implementation("org.graalvm.compiler:compiler:$graalVersion")
  implementation("org.graalvm.sdk:graal-sdk:$graalVersion")
  implementation("org.graalvm.sdk:launcher-common:$graalVersion")
  implementation("org.graalvm.truffle:truffle-api:$graalVersion")
  testImplementation("org.graalvm.compiler:compiler:$graalVersion")
  kapt("org.graalvm.truffle:truffle-api:$graalVersion")
  kapt("org.graalvm.truffle:truffle-dsl-processor:$graalVersion")
  testImplementation("org.junit.jupiter:junit-jupiter:5.5.2")
  implementation("org.jetbrains.kotlin:kotlin-script-runtime:1.4.30")
  implementation("org.jetbrains.kotlin:kotlin-reflect:1.4.30")
}

java {
  sourceCompatibility = JavaVersion.VERSION_1_8
  targetCompatibility = JavaVersion.VERSION_1_8
}

tasks.withType<KotlinCompile> {
  kotlinOptions {
    jvmTarget = JavaVersion.VERSION_1_8.toString()
    freeCompilerArgs += "-Xno-param-assertions"
    freeCompilerArgs += "-Xno-call-assertions"
    freeCompilerArgs += "-Xno-receiver-assertions"
    freeCompilerArgs += "-Xopt-in=kotlin.RequiresOptIn"
  }
}

fun<R> SourceSet.kotlin(f: KotlinSourceSet.() -> R): R =
  ((this as HasConvention).convention.getPlugin(KotlinSourceSet::class.java)).f()
val SourceSet.kotlin: SourceDirectorySet get() = kotlin { kotlin }

sourceSets {
  main {
    java.srcDir("src")
    kotlin.srcDirs("src")
  }
  test {
    kotlin.srcDirs("test")
  }
  val bench by creating {
    dependencies {
      "kaptBench"("org.openjdk.jmh:jmh-generator-annprocess:1.22")
    }
    java.srcDir("bench")
    kotlin.srcDir("bench")
    kotlin {
      dependencies {
        implementation(kotlin("stdlib"))
        implementation("org.openjdk.jmh:jmh-core:1.22")
      }
    }
    compileClasspath += sourceSets["main"].output
    runtimeClasspath += sourceSets["main"].output
    compileClasspath += configurations.runtimeClasspath.get()
    runtimeClasspath += configurations.runtimeClasspath.get()
  }
}

// disable for private
buildScan {
  termsOfServiceUrl = "https://gradle.com/terms-of-service"
  termsOfServiceAgree = "yes"
  if (System.getenv("CI") != null) { // on travis, always publish build-scan
    publishAlways()
    tag("CI")
  }
  tag(System.getProperty("os.name"))
}

gitPublish {
  repoUri.set(
    if (System.getenv("CI") != null) "https://github.com/acertain/trufflestg.git"
    else "git@github.com:acertain/trufflestg.git"
  )
  branch.set("gh-pages")
  repoDir.set(file("$buildDir/javadoc"))
  contents { from("etc/gh-pages") }
}

tasks.getByName("gitPublishCommit").dependsOn(":dokka")

application {
  mainClassName = "trufflestg.Launcher"
  applicationDefaultJvmArgs = listOf(
    "-XX:+UnlockExperimentalVMOptions",
    "-XX:+EnableJVMCI",
    "--module-path=${compiler.asPath}",
    "--upgrade-module-path=${compiler.asPath}",
    "--add-opens=jdk.internal.vm.compiler/org.graalvm.compiler.truffle.runtime=ALL-UNNAMED",
    "-Dtruffle.class.path.append=@TRUFFLESTG_APP_HOME@/lib/trufflestg-${project.version}.jar"
  )
}

var rootBuildDir = project.buildDir

val graalArgs = listOf(
  "-XX:+UnlockExperimentalVMOptions",
  "-XX:+EnableJVMCI",
  "--module-path=${compiler.asPath}",
  "--upgrade-module-path=${compiler.asPath}",
//  "-XX:-UseJVMCIClassLoader",
  "-Dgraalvm.locatorDisabled=true",
  "-Dtruffle.class.path.append=build/libs/trufflestg-${project.version}.jar",
  "--add-opens=jdk.internal.vm.compiler/org.graalvm.compiler.truffle.runtime=ALL-UNNAMED",
  "--add-opens=org.graalvm.truffle/com.oracle.truffle.api.source=ALL-UNNAMED",

  "-Dgraal.Dump=Truffle",
  "-Dgraal.PrintGraph=Network",
  "-Dgraal.CompilationFailureAction=ExitVM",
//  "-Dgraal.TraceTruffleCompilation=true",
//  "-Dgraal.TraceTruffleSplitting=true",
//  "-Dgraal.TruffleTraceSplittingSummary=true",
//  "-Dgraal.TraceTruffleAssumptions=true",
//  "-Dgraal.TraceTruffleTransferToInterpreter=true",
  // limit size of graphs for easier visualization
//  "-Dgraal.TruffleMaximumRecursiveInlining=0",
//  "-Dgraal.LoopPeeling=false",
  "-Xss32m"
)

tasks.test {
  useJUnitPlatform()
  testLogging {
    events("passed","skipped","failed")
    showStandardStreams = true
  }
  dependsOn(":jar")
  jvmArgs = graalArgs
}


tasks.register("bench", JavaExec::class) {
  dependsOn("benchClasses", "jar")
//  dependsOn(sourceSets["bench"].getJarTaskName())
  classpath = sourceSets["bench"].runtimeClasspath + sourceSets["bench"].compileClasspath
  main = "org.openjdk.jmh.Main"
  jvmArgs = graalArgs
}


tasks.getByName<Jar>("jar") {
  exclude("jre/**")
  exclude("META-INF/symlinks")
  exclude("META-INF/permissions")
  archiveBaseName.set("trufflestg")
  manifest {
    attributes["Main-Class"] = "trufflestg.Launcher"
    attributes["Class-Path"] = configurations.runtimeClasspath.get().files.joinToString(separator = " ") { it.absolutePath }
  }
}


tasks.register("componentJar", Jar::class) {
  archiveBaseName.set("trufflestg-component")
  from(tasks.getByPath(":processResources"))
  description = "Build the trufflestg component for graal"
  from("LICENSE.txt") { rename("LICENSE.txt","LICENSE_trufflestg.txt") }
  from("LICENSE.txt") { rename("LICENSE.txt","languages/trufflestg/LICENSE.txt") }
  from(tasks.getByPath(":startScripts")) {
    rename("(.*)","languages/trufflestg/bin/$1")
    filesMatching("**/trufflestg") {
      filter(ReplaceTokens::class, "tokens" to mapOf("TRUFFLESTG_APP_HOME" to "\$APP_HOME"))
    }
    filesMatching("**/trufflestg.bat") {
      filter(ReplaceTokens::class, "tokens" to mapOf("TRUFFLESTG_APP_HOME" to "%~dp0.."))
    }
  }

  from(files(
    tasks.getByPath(":jar"),
    configurations.getByName("runtimeClasspath")
  )) {
    rename("(.*).jar","languages/trufflestg/lib/\$1.jar")
    exclude("graal-sdk*.jar", "truffle-api*.jar", "launcher-common*.jar") //, "annotations*.jar")
  }

  manifest {
    attributes["Bundle-Name"] = "trufflestg"
    attributes["Bundle-Description"] = "STG on Graal/Truffle"
    attributes["Bundle-DocURL"] = "https://github.com/acertain/trufflestg"
    attributes["Bundle-Symbolic-Name"] = "trufflestg"
    attributes["Bundle-Version"] = "0.0-SNAPSHOT"
    attributes["Bundle-RequireCapability"] = "org.graalvm;filter:=\"(&(graalvm_version=$graalVersion)(os_arch=amd64))\""
    attributes["x-GraalVM-Polyglot-Part"] = "True"
  }
}

val componentJar = tasks.getByName<Jar>("componentJar")

distributions.main {
  baseName = "trufflestg"
  contents {
    from(componentJar)
    exclude( "graal-sdk*.jar", "truffle-api*.jar", "launcher-common*.jar", "compiler.jar")
    filesMatching("**/trufflestg") {
      filter(ReplaceTokens::class, "tokens" to mapOf("TRUFFLESTG_APP_HOME" to "\$APP_HOME"))
    }
    filesMatching("**/trufflestg.bat") {
      filter(ReplaceTokens::class, "tokens" to mapOf("TRUFFLESTG_APP_HOME" to "%~dp0.."))
    }
    from("LICENSE.txt")
  }
}


tasks.withType<ProcessResources> {
  from("etc/native-image.properties") {
    // TODO: expand more properties
    expand(project.properties)
    rename("native-image.properties","languages/trufflestg/native-image.properties")
  }
  from(files("etc/symlinks","etc/permissions")) {
    rename("(.*)","META-INF/$1")
  }
}

tasks.withType<DokkaTask> {
  outputFormat = "html"
  outputDirectory = gitPublish.repoDir.get().getAsFile().getAbsolutePath()
  dependsOn(":gitPublishReset")
  configuration {
    jdkVersion = 8
    includes = listOf("etc/module.md")
    arrayOf("src","test").forEach {
      sourceLink {
        path = "$it"
        url = "https://github.com/acertain/trufflestg/blob/master/$it"
        lineSuffix = "#L"
      }
    }
  }
}

tasks.register("pages") {
  description = "Publish documentation"
  group = "documentation"
  dependsOn(":gitPublishPush")
}

val os : String? = System.getProperty("os.name")
logger.info("os = {}",os)

// can i just tweak this one now?
tasks.replace("run", JavaExec::class.java).run {
//  enableAssertions = true
  description = "Run trufflestg directly from the working directory"
  dependsOn(":jar")
  classpath = sourceSets["main"].runtimeClasspath
  jvmArgs = graalArgs
  main = "trufflestg.Launcher"
}

// assumes we are building on graal
tasks.register("runInstalled", Exec::class) {
  group = "application"
  description = "Run a version of trufflestg from the distribution dir"
  dependsOn(":installDist")
  executable = "$buildDir/install/trufflestg/bin/trufflestg"
  outputs.upToDateWhen { false }
}

var graalHome : String? = System.getenv("GRAALVM_HOME")

if (graalHome == null) {
  val javaHome : String? = System.getenv("JAVA_HOME")
  if (javaHome != null) {
    logger.info("checking JAVA_HOME {} for Graal install", javaHome)
    val releaseFile = file("${javaHome}/release")
    if (releaseFile.exists()) {
      val releaseProps = Properties()
      releaseProps.load(releaseFile.inputStream())
      val ver = releaseProps.getProperty("GRAALVM_VERSION")
      if (ver != null) {
        logger.info("graal version {} detected in JAVA_HOME", ver)
        graalHome = javaHome
      }
    }
  }
}

logger.info("graalHome = {}", graalHome)

if (graalHome != null) {
  val graalBinDir = if (os == "Linux") graalHome else "$graalHome/bin"
  tasks.register("register", Exec::class) {
    group = "installation"
    dependsOn(componentJar)
    description = "Register trufflestg with graal"
    commandLine = listOf(
      "$graalBinDir/gu",
      "install",
      "-f",
      "-L",
      "build/libs/trufflestg-component-${project.version}.jar"
    )
  }
  // assumes we are building on graal
  tasks.register("runRegistered", Exec::class) {
    group = "application"
    description = "Run a registered version of trufflestg"
    dependsOn(":register")
    executable = "$graalBinDir/trufflestg"
    environment("JAVA_HOME", graalHome as String)
    outputs.upToDateWhen { false }
  }
  tasks.register("unregister", Exec::class) {
    group = "installation"
    description = "Unregister trufflestg with graal"
    commandLine = listOf(
      "$graalBinDir/gu",
      "remove",
      "trufflestg"
    )
  }
}
