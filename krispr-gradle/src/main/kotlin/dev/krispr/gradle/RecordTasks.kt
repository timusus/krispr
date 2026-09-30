package dev.krispr.gradle

import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import java.util.concurrent.ConcurrentSkipListSet

/**
 * The paths of every `krisprRecord` task in the build, so each `krisprRun` can be ordered after all of
 * them (#41) without reading other projects' tasks, which Gradle's isolated projects forbid. Each module
 * adds its own path when the plugin is applied; `krisprRun` reads the set lazily, when the task graph is
 * built, by which time every module that has a requested task is configured.
 */
abstract class RecordTasks : BuildService<BuildServiceParameters.None> {
    val paths: MutableSet<String> = ConcurrentSkipListSet()

    companion object {
        const val NAME = "krisprRecordTasks"
    }
}
