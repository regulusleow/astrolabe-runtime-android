import groovy.json.JsonSlurper
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import org.w3c.dom.Document
import java.io.InputStream
import java.util.jar.JarInputStream
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

@DisableCachingByDefault(because = "This task only validates generated publication artifacts")
abstract class VerifyDistributionArtifactTask : DefaultTask() {
    /** Fused Android Runtime AAR to validate. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val aarFile: RegularFileProperty

    /** Generated Maven POM to validate. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val pomFile: RegularFileProperty

    /** Merged source JAR published with the fused Runtime AAR. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourcesJarFile: RegularFileProperty

    /** Gradle module metadata published with the fused Runtime AAR. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val moduleMetadataFile: RegularFileProperty

    @TaskAction
    fun verify() {
        verifyAar()
        verifySourcesJar()
        verifyPom(parseXml(pomFile.get().asFile.inputStream()))
        verifyModuleMetadata()
    }

    private fun verifyAar() {
        ZipFile(aarFile.get().asFile).use { archive ->
            val classesEntry = archive.getEntry("classes.jar")
                ?: error("Fused AAR does not contain classes.jar")
            val classNames = archive.getInputStream(classesEntry).use(::jarEntryNames)
            val missingClasses = requiredClasses - classNames
            check(missingClasses.isEmpty()) {
                "Fused AAR is missing Runtime classes: ${missingClasses.sorted()}"
            }

            val manifestEntry = archive.getEntry("AndroidManifest.xml")
                ?: error("Fused AAR does not contain AndroidManifest.xml")
            val manifest = archive.getInputStream(manifestEntry).use(::parseXml)
            val providerNodes = manifest.getElementsByTagName("provider")
            val initializerFound = (0 until providerNodes.length).any { index ->
                providerNodes.item(index).attributes
                    ?.getNamedItemNS(androidNamespace, "name")
                    ?.nodeValue == initializerClassName
            }
            check(initializerFound) {
                "Fused AAR manifest does not register AstrolabeRuntimeInitializer"
            }
        }
    }

    private fun verifySourcesJar() {
        val sourceNames = sourcesJarFile.get().asFile.inputStream().use(::jarEntryNames)
        val missingSources = requiredSources - sourceNames
        check(missingSources.isEmpty()) {
            "Fused source JAR is missing Runtime sources: ${missingSources.sorted()}"
        }
    }

    private fun verifyPom(pom: Document) {
        check(pom.getElementsByTagName("groupId").item(0)?.textContent == publicationGroup)
        check(
            pom.getElementsByTagName("artifactId").item(0)?.textContent ==
                publicationArtifact
        )

        val dependencies = pom.getElementsByTagName("dependency")
        val dependencyCoordinates = (0 until dependencies.length).map { index ->
            val children = dependencies.item(index).childNodes
            val values = (0 until children.length).associate { childIndex ->
                val child = children.item(childIndex)
                child.nodeName to child.textContent.trim()
            }
            "${values["groupId"]}:${values["artifactId"]}:${values["version"]}"
        }
        verifyDependencyCoordinates(dependencyCoordinates)
    }

    private fun verifyModuleMetadata() {
        val metadata = requireMap(
            JsonSlurper().parse(moduleMetadataFile.get().asFile),
            "Gradle module metadata"
        )
        val component = requireMap(metadata["component"], "Gradle module component")
        check(component["group"] == publicationGroup)
        check(component["module"] == publicationArtifact)

        val variants = requireList(metadata["variants"], "Gradle module variants")
        val dependencyCoordinates = variants.flatMap { variantValue ->
            val variant = requireMap(variantValue, "Gradle module variant")
            requireList(
                variant["dependencies"] ?: emptyList<Any>(),
                "Gradle module dependencies"
            ).map { dependencyValue ->
                val dependency = requireMap(
                    dependencyValue,
                    "Gradle module dependency"
                )
                val version = requireMap(
                    dependency["version"],
                    "Gradle module dependency version"
                )
                "${dependency["group"]}:${dependency["module"]}:${version["requires"]}"
            }
        }
        verifyDependencyCoordinates(dependencyCoordinates)
    }

    private fun verifyDependencyCoordinates(dependencyCoordinates: List<String>) {
        check(protocolCoordinate in dependencyCoordinates) {
            "Runtime publication must depend on Astrolabe Protocol Kotlin 2.0.0"
        }
        check(dependencyCoordinates.none(::isInternalCoordinate)) {
            "Runtime publication leaks internal or local project coordinates: " +
                dependencyCoordinates
        }
        check(dependencyCoordinates.none { coordinate ->
            coordinate.startsWith("$constraintLayoutGroup:")
        }) {
            "Runtime publication leaks optional ConstraintLayout dependencies: " +
                dependencyCoordinates
        }
    }

    private fun requireMap(value: Any?, context: String): Map<*, *> =
        value as? Map<*, *> ?: error("$context must be an object")

    private fun requireList(value: Any?, context: String): List<*> =
        value as? List<*> ?: error("$context must be an array")

    private fun jarEntryNames(input: InputStream): Set<String> = JarInputStream(input).use { archive ->
        buildSet {
            var entry = archive.nextJarEntry
            while (entry != null) {
                add(entry.name)
                entry = archive.nextJarEntry
            }
        }
    }

    private fun parseXml(input: InputStream): Document = input.use {
        DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
        }.newDocumentBuilder().parse(it)
    }

    private fun isInternalCoordinate(coordinate: String): Boolean =
        coordinate.split(":").getOrNull(1) in internalArtifacts

    private companion object {
        const val androidNamespace = "http://schemas.android.com/apk/res/android"
        const val initializerClassName =
            "dev.astrolabe.runtime.AstrolabeRuntimeInitializer"
        const val publicationGroup = "io.github.regulusleow"
        const val publicationArtifact = "astrolabe-runtime-android"
        const val protocolCoordinate =
            "io.github.regulusleow:astrolabe-protocol-kotlin:2.0.0"
        const val constraintLayoutGroup = "androidx.constraintlayout"

        val internalArtifacts = setOf(
            "astrolabe-runtime",
            "astrolabe-runtime-core",
            "astrolabe-runtime-view",
            "AstrolabeProtocolKotlin"
        )

        val requiredClasses = setOf(
            "dev/astrolabe/runtime/AstrolabeRuntime.class",
            "dev/astrolabe/runtime/AstrolabeRuntimeInitializer.class",
            "dev/astrolabe/runtime/core/RuntimeServer.class",
            "dev/astrolabe/runtime/view/AndroidViewHierarchyCollector.class"
        )

        val requiredSources = setOf(
            "dev/astrolabe/runtime/AstrolabeRuntime.kt",
            "dev/astrolabe/runtime/core/RuntimeServer.kt",
            "dev/astrolabe/runtime/view/AndroidViewHierarchyCollector.kt"
        )
    }
}
