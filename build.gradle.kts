import org.gradle.api.artifacts.ProjectDependency

private val boundaryModulePaths = listOf(
    ":astrolabe-runtime-core",
    ":astrolabe-runtime-view",
    ":astrolabe-runtime"
)

private val verifyModuleBoundaries = tasks.register("verifyModuleBoundaries") {
    group = "verification"
    description = "Verifies the declared Android Runtime module dependency direction."

    doLast {
        fun dependencies(modulePath: String): Set<String> {
            val inputName = modulePath.removePrefix(":") + "Dependencies"
            val dependencyPaths = inputs.properties[inputName] as? List<*>
                ?: error("Missing module boundary input for $modulePath")
            return dependencyPaths.filterIsInstance<String>().toSet()
        }

        val coreDependencies = dependencies(":astrolabe-runtime-core")
        val viewDependencies = dependencies(":astrolabe-runtime-view")
        val facadeDependencies = dependencies(":astrolabe-runtime")
        val distributionDependencies = dependencies(":astrolabe-runtime-distribution")

        check(coreDependencies.isEmpty()) {
            "Runtime Core cannot depend on another project module: $coreDependencies"
        }
        check(viewDependencies == setOf(":astrolabe-runtime-core")) {
            "Runtime View must depend only on Runtime Core: $viewDependencies"
        }
        check(
            facadeDependencies == setOf(
                ":astrolabe-runtime-core",
                ":astrolabe-runtime-view"
            )
        ) {
            "Runtime facade must depend only on Runtime Core and Runtime View: $facadeDependencies"
        }
        check(
            distributionDependencies == setOf(
                ":astrolabe-runtime",
                ":astrolabe-runtime-core",
                ":astrolabe-runtime-view"
            )
        ) {
            "Runtime distribution must fuse the facade, Core, and View modules: " +
                distributionDependencies
        }
    }
}

gradle.projectsEvaluated {
    boundaryModulePaths.forEach { modulePath ->
        val module = project(modulePath)
        val declaredDependencies = module.configurations
            .filter { configuration -> configuration.isCanBeDeclared }
            .flatMap { configuration -> configuration.dependencies }
            .filterIsInstance<ProjectDependency>()
            .map(ProjectDependency::getPath)
            .filterNot { dependencyPath -> dependencyPath == modulePath }
            .distinct()
            .sorted()
        verifyModuleBoundaries.configure {
            inputs.property(
                modulePath.removePrefix(":") + "Dependencies",
                declaredDependencies
            )
        }
    }
    val distributionDependencies = project(":astrolabe-runtime-distribution")
        .configurations
        .getByName("include")
        .dependencies
        .filterIsInstance<ProjectDependency>()
        .map(ProjectDependency::getPath)
        .distinct()
        .sorted()
    verifyModuleBoundaries.configure {
        inputs.property("astrolabe-runtime-distributionDependencies", distributionDependencies)
    }
}
