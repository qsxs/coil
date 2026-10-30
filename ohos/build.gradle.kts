import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import dev.drewhamilton.poko.gradle.PokoPluginExtension
import java.security.MessageDigest
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.artifacts.component.RootComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.Usage
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.publish.maven.tasks.PublishToMavenRepository
import org.gradle.jvm.tasks.Jar
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.tasks.KotlinNativeCompile

plugins {
    kotlin("multiplatform") apply false
    kotlin("plugin.compose") apply false
    id("dev.drewhamilton.poko") apply false
}

fun asMap(value: Any?): Map<String, Any> = (value as Map<*, *>).entries.associate {
    it.key.toString() to requireNotNull(it.value)
}
fun readMap(path: File) = asMap(JsonSlurper().parse(path))
fun sha(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
fun allFiles(file: File): List<File> = if (file.isDirectory) file.walkTopDown().filter(File::isFile).toList() else listOf(file)
fun fileRecords(files: Iterable<File>) = files.filter(File::isFile).distinct().sortedBy { it.path }.map {
    mapOf("path" to it.absolutePath, "sha256" to sha(it), "size" to it.length())
}
val cfg = readMap(file("port-config.json"))
val models = asMap(cfg.getValue("modules"))
val pins = readMap(file("dependency-pins.json"))
val metadataPins = readMap(file("metadata-pins.json"))
val metadataTools = readMap(file("metadata-toolchain.json"))
val launcherPins = asMap(metadataTools.getValue("launcherPins"))
val poko = asMap(cfg.getValue("poko"))
val portWork = file(providers.gradleProperty("portWork").get()).canonicalFile
val fingerprint = providers.gradleProperty("portFingerprint").get()
require(fingerprint.matches(Regex("[0-9a-f]{64}")))
val portVersion = providers.gradleProperty("portVersion").get()
val prepared = readMap(portWork.resolve("compiled-source-manifest.json"))
require(prepared["fingerprint"] == fingerprint && prepared["sourceVariant"] == cfg["sourceVariant"])
layout.buildDirectory.set(portWork.resolve("build/root"))
fun pinned(key: String) = "$key:${pins.getValue(key)}"
fun model(module: String) = asMap(models.getValue(module))
fun sourceRoots(module: String) = asMap(model(module).getValue("sourceRoots"))
fun metadataTask(source: String) = if (source == "commonMain") "compileCommonMainWithMetadataCli"
    else "compile${source.replaceFirstChar { it.uppercase() }}KotlinMetadata"
fun transformTask(source: String) = "transform${source.replaceFirstChar { it.uppercase() }}DependenciesMetadata"
fun metadataSource(task: Task): String? = when {
    task.name == "compileCommonMainWithMetadataCli" -> "commonMain"
    task is KotlinNativeCompile && task.name.endsWith("KotlinMetadata") ->
        task.name.removePrefix("compile").removeSuffix("KotlinMetadata").replaceFirstChar { it.lowercase() }
    else -> null
}
fun compilerTask(task: Task): KotlinNativeCompile? = when {
    task is KotlinNativeCompile -> task
    task.name == "compileCommonMainWithMetadataCli" -> task.project.tasks.named<KotlinNativeCompile>("compileCommonMainKotlinMetadata").get()
    else -> null
}
fun languageOptions(task: KotlinNativeCompile): Map<String, Any?> = mapOf(
    "languageVersion" to task.compilerOptions.languageVersion.orNull?.version,
    "apiVersion" to task.compilerOptions.apiVersion.orNull?.version,
    "optIns" to task.compilerOptions.optIn.get().sorted(),
    "freeCompilerArgs" to task.compilerOptions.freeCompilerArgs.get())
fun writeEvidence(name: String, data: Any) {
    val path = portWork.resolve("evidence/$name.json")
    path.parentFile.mkdirs()
    path.writeText(JsonOutput.prettyPrint(JsonOutput.toJson(data)))
}
fun requireGate(name: String) {
    val gate = portWork.resolve("evidence/$name.json")
    check(gate.isFile && readMap(gate)["fingerprint"] == fingerprint) { "Missing or stale Coil gate: $name" }
}
fun effectiveDependencies(task: Task): List<String> {
    val visited = mutableSetOf<Task>()
    fun visit(current: Task) {
        gradle.taskGraph.getDependencies(current).forEach { if (visited.add(it)) visit(it) }
    }
    visit(task)
    return visited.map { it.path }.sorted()
}
fun outputBinding(compilation: org.jetbrains.kotlin.gradle.plugin.KotlinCompilation<*>, task: Task) =
    mapOf("sourceSet" to compilation.name, "roots" to compilation.output.classesDirs.files.map { it.absolutePath },
        "files" to fileRecords(compilation.output.classesDirs.files.flatMap(::allFiles)),
        "builtBy" to compilation.output.classesDirs.buildDependencies.getDependencies(task).map { it.path }.sorted())
fun parentOutputs(task: Task): List<Map<String, Any>> {
    val source = metadataSource(task) ?: return emptyList()
    val kmp = task.project.extensions.getByType<KotlinMultiplatformExtension>()
    return kmp.sourceSets.getByName(source).dependsOn.map { parent ->
        val compilation = kmp.targets.getByName("metadata").compilations.getByName(parent.name)
        outputBinding(compilation, task)
    }
}
fun directProjectOutputs(task: Task): List<Map<String, Any>> {
    val source = metadataSource(task) ?: return emptyList()
    return (model(task.project.name)["projectApiDependencies"] as List<*>).flatMap { upstream ->
        val child = project(":$upstream")
        val upstreamKmp = child.extensions.getByType<KotlinMultiplatformExtension>()
        val ancestors = mutableSetOf<String>()
        fun visit(name: String) {
            if (ancestors.add(name)) upstreamKmp.sourceSets.getByName(name).dependsOn.forEach { visit(it.name) }
        }
        visit(if (upstreamKmp.sourceSets.findByName(source) != null) source else "commonMain")
        upstreamKmp.targets.getByName("metadata").compilations.filter {
            it.name in ancestors && sourceRoots(upstream.toString()).containsKey(it.name)
        }.map { mapOf("module" to upstream.toString()) + outputBinding(it, task) }
    }
}
fun producerRecord(task: Task): Map<String, Any?> {
    val state = task.state
    check(gradle.taskGraph.hasTask(task) && state.executed && task.enabled && state.failure == null &&
        !state.noSource && state.skipMessage in listOf(null, "UP-TO-DATE", "FROM-CACHE")) { "Invalid producer state: ${task.path}" }
    val inputs = task.inputs.files.files.flatMap(::allFiles)
    val roots = task.outputs.files.files.filter { it.exists() }
    val output = fileRecords(roots.flatMap(::allFiles))
    val compiler = compilerTask(task)
    check(output.isNotEmpty()) { "Missing producer output: ${task.path}" }
    return mapOf("task" to task.path,
        "state" to mapOf("inTaskGraph" to true, "executed" to state.executed, "enabled" to task.enabled,
            "noSource" to state.noSource, "failure" to null, "skipMessage" to state.skipMessage),
        "inputs" to fileRecords(inputs), "sourceInputs" to fileRecords(inputs.filter { it.extension == "kt" }),
        "outputs" to output, "outputRoots" to roots.map { it.absolutePath },
        "libraries" to if (compiler != null) fileRecords(compiler.libraries.files.flatMap(::allFiles)) else emptyList<Map<String, Any>>(),
        "dependencies" to effectiveDependencies(task),
        "compilerPlugins" to if (compiler != null) fileRecords(compiler.compilerPluginClasspath?.files.orEmpty()) else emptyList<Map<String, Any>>(),
        "compilerPluginOptions" to if (compiler != null) compiler.compilerPluginCommandLine else emptyList<String>(),
        "compilerOptIns" to if (compiler != null) compiler.compilerOptions.optIn.get().sorted() else emptyList<String>(),
        "compilerLanguageOptions" to if (compiler != null) languageOptions(compiler) else emptyMap<String, Any>(),
        "parentCompilationOutputs" to parentOutputs(task), "directProjectMetadata" to directProjectOutputs(task),
        "evidenceLimit" to if (task.name == "compileCommonMainWithMetadataCli") "PUBLIC_METADATA_CLI_ARGV_AND_TASK_BINDING"
            else "PUBLIC_TASK_INPUT_OUTPUT_AND_BUILTBY_NOT_FULL_COMPILER_ARGV") +
        if (task.name == "compileCommonMainWithMetadataCli") {
            val cli = readMap(task.project.layout.buildDirectory.file("metadata-cli/commonMain/producer.json").get().asFile)
            mapOf("commonMetadataCli" to cli, "metadataOutputs" to cli.getValue("outputs"))
        } else emptyMap()
}
fun componentIdentity(id: org.gradle.api.artifacts.component.ComponentIdentifier): Map<String, String> = when (id) {
    is ModuleComponentIdentifier -> mapOf("kind" to "module", "group" to id.group, "module" to id.module, "version" to id.version)
    is ProjectComponentIdentifier -> mapOf("kind" to "project", "project" to id.projectPath.removePrefix(":"))
    is RootComponentIdentifier -> mapOf("kind" to "root", "displayName" to id.displayName)
    else -> error("Unsupported Coil component identity: $id")
}
fun resolutionEvidence(child: Project, configuration: Configuration, scope: String, label: String = configuration.name): Map<String, Any> {
    val graph = configuration.incoming.resolutionResult
    val components = graph.allComponents.map { component ->
        componentIdentity(component.id) + mapOf("selectionReason" to component.selectionReason.descriptions.map {
            mapOf("cause" to it.cause.toString(), "description" to it.description)
        })
    }
    val artifacts = configuration.incoming.artifacts.artifacts.map { artifact ->
        componentIdentity(artifact.id.componentIdentifier) + mapOf("attributes" to artifact.variant.attributes.keySet().associate {
            it.name to artifact.variant.attributes.getAttribute(it).toString()
        }, "files" to fileRecords(allFiles(artifact.file)))
    }
    val edges = graph.allDependencies.map { dependency ->
        check(dependency is ResolvedDependencyResult) { "Unresolved ${child.path}:$label dependency: ${dependency.requested}" }
        mapOf("requested" to dependency.requested.displayName, "selected" to componentIdentity(dependency.selected.id),
            "from" to dependency.from.id.displayName, "selectionReason" to dependency.selected.selectionReason.descriptions.map {
                mapOf("cause" to it.cause.toString(), "description" to it.description)
            })
    }
    return mapOf("module" to child.name, "configuration" to label, "scope" to scope,
        "components" to components, "artifacts" to artifacts, "edges" to edges)
}
fun loadedPluginEvidence(child: Project, id: String): Map<String, Any>? {
    val plugin = child.plugins.findPlugin(id) ?: return null
    val type = plugin.javaClass
    val location = type.protectionDomain?.codeSource?.location
    val origin = if (location?.protocol == "file") File(location.toURI()) else null
    return mapOf("module" to child.name, "pluginId" to id, "class" to type.name,
        "location" to (location?.toExternalForm() ?: "UNKNOWN"), "files" to fileRecords(origin?.let(::allFiles).orEmpty()))
}

subprojects {
    val moduleName = name
    val moduleModel = model(moduleName)
    group = cfg.getValue("group")
    version = portVersion
    layout.buildDirectory.set(portWork.resolve("build/$moduleName"))
    plugins.withId("dev.drewhamilton.poko") {
        // Provider 求值前设置原源自定义 marker，不注入默认 annotations 依赖。
        extensions.configure<PokoPluginExtension> {
            pokoAnnotation.set(poko.getValue("annotation").toString())
        }
    }
    plugins.withId("org.jetbrains.kotlin.multiplatform") {
        extensions.configure<KotlinMultiplatformExtension> {
            ohosX64()
            ohosArm64()
            sourceSets {
                val parents = asMap(moduleModel.getValue("sourceSetParents"))
                parents.keys.forEach { maybeCreate(it) }
                parents.forEach { (name, value) ->
                    val source = getByName(name)
                    (value as List<*>).forEach { source.dependsOn(getByName(it.toString())) }
                    val sourceModel = sourceRoots(moduleName)[name]?.let(::asMap)
                    source.kotlin.setSrcDirs(if (sourceModel == null) emptyList<File>() else
                        listOf(portWork.resolve("compiled/$moduleName/${sourceModel.getValue("root")}")))
                    if (sourceModel != null) source.kotlin.include((sourceModel.getValue("includes") as List<*>).map { it.toString() })
                }
                getByName("commonTest").kotlin.setSrcDirs(emptyList<File>())
                getByName("commonMain").dependencies {
                    (moduleModel["projectApiDependencies"] as List<*>).forEach { api(project(":$it")) }
                }
                asMap(moduleModel["dependencies"] ?: emptyMap<String, Any>()).forEach { (source, scopes) ->
                    getByName(source).dependencies {
                        asMap(scopes).forEach { (scope, keys) ->
                            (keys as List<*>).forEach {
                                when (scope) {
                                    "api" -> api(pinned(it.toString()))
                                    "implementation" -> implementation(pinned(it.toString()))
                                    else -> error("Unsupported dependency scope: $scope")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    configurations.configureEach {
        val metadata = name.endsWith("ResolvableDependenciesMetadata") || name == "allSourceSetsCompileDependenciesMetadata"
        val native = name in setOf("ohosX64CompileKlibraries", "ohosArm64CompileKlibraries",
            "ohosX64CompilationDependenciesMetadata", "ohosArm64CompilationDependenciesMetadata")
        if ((metadata || native) && !name.contains("Test", ignoreCase = true)) resolutionStrategy.eachDependency {
            val key = "${requested.group}:${requested.name}"
            val selected = (if (metadata) metadataPins[key] ?: pins[key] else pins[key])
                ?: error("Unreviewed Coil dependency: $name $key:${requested.version}")
            val resolved = if (selected.toString() == "__PORT_VERSION__") portVersion else selected.toString()
            if (requested.version != resolved) useVersion(resolved)
        }
    }
    tasks.withType<KotlinNativeCompile>().configureEach {
        // 正常 shared metadata 和两个 Native leaf 都使用原源要求的 ERROR 级 opt-in。
        compilerOptions.optIn.addAll((cfg.getValue("compilerOptIns") as List<*>).map { it.toString() })
        doFirst { requireGate("closure-verification") }
    }
    plugins.withId("maven-publish") {
        extensions.configure<PublishingExtension> {
            repositories { maven { name = "Port"; url = uri(portWork.resolve("maven")) } }
            publications.withType<MavenPublication>().configureEach {
                artifact(portWork.resolve("legal/META-INF/LICENSE")) {
                    classifier = "license"
                    extension = "txt"
                }
                pom {
                    name.set(moduleName)
                    description.set("Coil 3.3.0-1.1.0-02 preserved raw sources; approved OHOS JvmName annotation patch; private dual ABI candidate")
                    // 许可证原文由发布门禁核对并附入 JAR；不声明未知 SCM commit。
                    val legal = asMap(cfg.getValue("publicationPom"))
                    licenses { license {
                        name.set(legal.getValue("licenseName").toString())
                        url.set(legal.getValue("licenseUrl").toString())
                        distribution.set(legal.getValue("licenseDistribution").toString())
                    } }
                    scm {
                        connection.set(legal.getValue("scmConnection").toString())
                        developerConnection.set(legal.getValue("scmDeveloperConnection").toString())
                        url.set(legal.getValue("scmUrl").toString())
                    }
                }
            }
        }
    }
    tasks.withType<PublishToMavenRepository>().configureEach {
        doFirst {
            listOf("metadata", "native-x64", "native-arm64").forEach { requireGate("$it-verification") }
            check(portWork.resolve("legal/META-INF/LICENSE").isFile) { "Missing exact distribution license" }
        }
    }
    tasks.withType<Jar>().configureEach {
        if (name.endsWith("SourcesJar", ignoreCase = true)) {
            from(portWork.resolve("legal"))
            from(listOf(rootProject.file("source-patch.json"), rootProject.file("source-inputs.json"))) { into("META-INF/upstream") }
        }
    }
}

// 仅替换 commonMain；其余 metadata 层和双 ABI 继续使用标准 KGP producer。
gradle.projectsEvaluated {
    check(metadataTools["compilerVersion"] == cfg["kotlin"])
    subprojects.forEach { child ->
        val kmp = child.extensions.getByType<KotlinMultiplatformExtension>()
        val compilation = kmp.targets.getByName("metadata").compilations.getByName("commonMain")
        val original = child.tasks.named<KotlinNativeCompile>("compileCommonMainKotlinMetadata").get()
        child.tasks.getByName("allMetadataJar")
        val originalDependencies = child.provider { original.taskDependencies.getDependencies(original).toList() }
        val launcher = child.configurations.create("commonMetadataCliLauncher") {
            isCanBeConsumed = false
            isCanBeResolved = true
            isVisible = false
            attributes {
                attribute(Usage.USAGE_ATTRIBUTE, child.objects.named(Usage.JAVA_RUNTIME))
                attribute(Category.CATEGORY_ATTRIBUTE, child.objects.named(Category.LIBRARY))
            }
            resolutionStrategy.eachDependency {
                val ga = "${requested.group}:${requested.name}"
                val fixed = launcherPins[ga]?.toString() ?: error("Unreviewed Coil metadata launcher dependency: $ga")
                if (requested.version != fixed) useVersion(fixed)
            }
        }
        child.dependencies.add(launcher.name, "org.jetbrains.kotlin:kotlin-compiler-embeddable:${metadataTools.getValue("compilerVersion")}")
        val output = child.layout.buildDirectory.dir("metadata-cli/commonMain/klib")
        val argumentsFile = child.layout.buildDirectory.file("metadata-cli/commonMain/arguments.txt")
        val evidenceFile = child.layout.buildDirectory.file("metadata-cli/commonMain/producer.json")
        val libraries = child.files(child.provider { original.libraries })
        val plugins = child.files(child.provider { original.compilerPluginClasspath })
        val sourceFiles = child.files((prepared.getValue("members") as List<*>).map(::asMap).filter {
            it["module"] == child.name && it["sourceSet"] == "commonMain"
        }.map { file(it.getValue("path")) })
        val compilerArguments = child.provider {
            val options = original.compilerOptions
            buildList {
                addAll(listOf("-classpath", libraries.files.sortedBy { it.path }.joinToString(File.pathSeparator) { it.invariantSeparatorsPath },
                    "-d", output.get().asFile.invariantSeparatorsPath, "-module-name", "${child.group}:${child.name}_commonMain",
                    "-Xtarget-platform=${metadataTools.getValue("targetPlatform")}", "-Xmulti-platform"))
                options.languageVersion.orNull?.let { addAll(listOf("-language-version", it.version)) }
                options.apiVersion.orNull?.let { addAll(listOf("-api-version", it.version)) }
                options.optIn.get().sorted().forEach { add("-opt-in=$it") }
                addAll(options.freeCompilerArgs.get())
                if (!plugins.isEmpty) add("-Xplugin=" + plugins.files.sortedBy { it.path }.joinToString(",") { it.invariantSeparatorsPath })
                original.compilerPluginCommandLine.forEach { addAll(listOf("-P", it)) }
                addAll(sourceFiles.files.sortedBy { it.path }.map { it.invariantSeparatorsPath })
            }
        }
        val cli = child.tasks.register<JavaExec>("compileCommonMainWithMetadataCli") {
            group = "build"
            description = "使用同版本公共 metadata 编译器编译原字节 commonMain。"
            dependsOn(originalDependencies)
            dependsOn("transformCommonMainDependenciesMetadata")
            classpath = launcher
            mainClass.set(metadataTools.getValue("mainClass").toString())
            inputs.files(sourceFiles, libraries, plugins, launcher)
            inputs.file(portWork.resolve("source-manifest.json"))
            inputs.file(portWork.resolve("compiled-source-manifest.json"))
            inputs.file(rootProject.file("source-patch.json"))
            inputs.file(rootProject.file("metadata-toolchain.json"))
            inputs.property("compilerArguments", compilerArguments)
            outputs.dir(output)
            outputs.file(argumentsFile)
            outputs.file(evidenceFile)
            args("@" + argumentsFile.get().asFile.absolutePath)
            doFirst {
                requireGate("closure-verification")
                val files = compilation.output.classesDirs
                check(files.files == setOf(output.get().asFile) &&
                    files.buildDependencies.getDependencies(this).map { it.path }.toSet() == setOf(path) && !original.enabled) {
                    "Coil common metadata output/builtBy was not replaced exclusively: ${child.path}"
                }
                val argFile = argumentsFile.get().asFile
                argFile.parentFile.mkdirs()
                argFile.writeText(compilerArguments.get().joinToString("\n", postfix = "\n") {
                    "\"" + it.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
                })
            }
            doLast {
                check(executionResult.get().exitValue == 0)
                val launcherRecords = launcher.resolvedConfiguration.resolvedArtifacts.map {
                    mapOf("gav" to it.moduleVersion.id.toString(), "path" to it.file.absolutePath,
                        "sha256" to sha(it.file), "size" to it.file.length())
                }.sortedBy { it.getValue("gav").toString() }
                val data = mapOf("kind" to metadataTools.getValue("producer"), "module" to child.name,
                    "task" to path, "exitCode" to executionResult.get().exitValue,
                    "mainClass" to mainClass.get(), "compilerVersion" to metadataTools.getValue("compilerVersion"),
                    "javaExecutable" to javaLauncher.get().executablePath.asFile.absolutePath, "launcherArguments" to args,
                    "sourceManifestSha256" to sha(portWork.resolve("source-manifest.json")),
                    "compiledSourceManifestSha256" to sha(portWork.resolve("compiled-source-manifest.json")),
                    "sourceVariant" to cfg.getValue("sourceVariant"),
                    "sourcePatch" to fileRecords(listOf(rootProject.file("source-patch.json"))).single(),
                    "arguments" to compilerArguments.get(), "argumentsFile" to fileRecords(listOf(argumentsFile.get().asFile)).single(),
                    "launcher" to launcherRecords, "plugins" to fileRecords(plugins.files),
                    "languageOptions" to languageOptions(original), "pluginOptions" to original.compilerPluginCommandLine,
                    "sources" to fileRecords(sourceFiles.files),
                    "libraries" to libraries.files.sortedBy { it.path }.map {
                        mapOf("path" to it.absolutePath, "files" to fileRecords(allFiles(it)))
                    }, "output" to output.get().asFile.absolutePath, "outputs" to fileRecords(allFiles(output.get().asFile)),
                    "outputBinding" to outputBinding(compilation, this),
                    "retiredTask" to mapOf("path" to original.path, "enabled" to original.enabled))
                evidenceFile.get().asFile.writeText(JsonOutput.prettyPrint(JsonOutput.toJson(data)))
            }
        }
        original.enabled = false
        compilation.output.classesDirs.setFrom(output)
        compilation.output.classesDirs.setBuiltBy(listOf(cli))
        // 不在 whenReady 解析尚未生成的映射文件；真实 producer 执行时核输出和 builtBy。
    }
}

// Resolve 只解析已登记的全套配置，不触发任何源码编译。
tasks.register("recordResolvedDependencies") {
    outputs.upToDateWhen { false }
    doLast {
        val configurations = subprojects.flatMap { child ->
            val parents = asMap(model(child.name).getValue("sourceSetParents"))
            val names = parents.keys.filter { !it.endsWith("X64Main") && !it.endsWith("Arm64Main") }
                .map { "${it}ResolvableDependenciesMetadata" } + listOf("ohosX64CompileKlibraries", "ohosArm64CompileKlibraries")
            names.map { name ->
                val configuration = child.configurations.getByName(name)
                check(configuration.isCanBeResolved) { "Not resolvable: ${child.path}:$name" }
                resolutionEvidence(child, configuration, if (name.endsWith("CompileKlibraries")) "native-runtime" else "metadata")
            }
        }
        // 工具图单独取证，不对其套用 Native runtime pins 或全组 force。
        val toolConfigurations = (listOf(rootProject) + subprojects).flatMap { child ->
            listOfNotNull(child.buildscript.configurations.findByName("classpath")?.let {
                resolutionEvidence(child, it, "jvm-build-tool", "buildscript:classpath")
            }) + child.configurations.filter {
                it.isCanBeResolved && it.name.contains("CompilerPluginClasspath") && !it.name.contains("Test", ignoreCase = true)
            }.map { resolutionEvidence(child, it, "jvm-compiler-plugin") }
        }
        val compilerPlugins = subprojects.flatMap { child ->
            val names = sourceRoots(child.name).keys.map(::metadataTask) + listOf("compileKotlinOhosX64", "compileKotlinOhosArm64")
            names.map { name ->
                val actualTask = child.tasks.getByName(name)
                val task = requireNotNull(compilerTask(actualTask))
                mapOf("module" to child.name, "task" to actualTask.path, "sourceTask" to task.path,
                    "files" to fileRecords(task.compilerPluginClasspath?.files.orEmpty()),
                    "options" to task.compilerPluginCommandLine, "languageOptions" to languageOptions(task))
            }
        }
        val loadedPlugins = subprojects.flatMap { child ->
            listOf("org.jetbrains.kotlin.multiplatform", "org.jetbrains.kotlin.plugin.compose", "dev.drewhamilton.poko")
                .mapNotNull { loadedPluginEvidence(child, it) }
        }
        writeEvidence("resolve", mapOf("fingerprint" to fingerprint, "configurations" to configurations,
            "sourceVariant" to cfg.getValue("sourceVariant"),
            "compiledSourceManifestSha256" to sha(portWork.resolve("compiled-source-manifest.json")),
            "toolConfigurations" to toolConfigurations, "compilerPlugins" to compilerPlugins, "loadedPlugins" to loadedPlugins,
            "metadataLaunchers" to subprojects.map { child ->
                resolutionEvidence(child, child.configurations.getByName("commonMetadataCliLauncher"), "jvm-metadata-launcher")
            },
            "candidateStatus" to "PHYSICAL_RESOLVE_ONLY_NOT_ACCEPTED_RUNTIME_ROUTE"))
    }
}

fun registerProducerStage(taskName: String, stage: String, target: String?) {
    tasks.register(taskName) {
        val producers = subprojects.flatMap { child ->
            if (target == null) listOf("${child.path}:allMetadataJar") + sourceRoots(child.name).keys.flatMap {
                listOf("${child.path}:${metadataTask(it)}", "${child.path}:${transformTask(it)}")
            } else listOf("${child.path}:compileKotlin${target.replaceFirstChar { it.uppercase() }}")
        }
        dependsOn(producers)
        outputs.upToDateWhen { false }
        doLast {
            writeEvidence(stage, mapOf("fingerprint" to fingerprint, "stage" to stage, "target" to target,
                "sourceManifestSha256" to sha(portWork.resolve("source-manifest.json")),
                "compiledSourceManifestSha256" to sha(portWork.resolve("compiled-source-manifest.json")),
                "sourceVariant" to cfg.getValue("sourceVariant"),
                "producers" to producers.map { producerRecord(project.tasks.getByPath(it)) }))
        }
    }
}
registerProducerStage("recordMetadataProducers", "metadata", null)
registerProducerStage("recordX64Producers", "native-x64", "ohosX64")
registerProducerStage("recordArm64Producers", "native-arm64", "ohosArm64")
tasks.register("publishAllPublicationsToPortRepository") {
    dependsOn(subprojects.map { "${it.path}:publishAllPublicationsToPortRepository" })
}
