package com.github.reactunitconverter.startup

import com.github.reactunitconverter.service.ProjectConfigService
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

/**
 * Project-opened activity. Re-runs px2rem config auto-detection for the opened project
 * so actions and inspections always have accurate settings.
 */
class AutoDetectConfigStartup : ProjectActivity {
    override suspend fun execute(project: Project) {
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                val svc = ProjectConfigService.getInstance(project)
                project.basePath?.let { svc.redetect(File(it)) }
            }
        }
    }
}
