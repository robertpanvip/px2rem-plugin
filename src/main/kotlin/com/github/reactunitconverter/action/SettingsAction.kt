package com.github.reactunitconverter.action

import com.github.reactunitconverter.service.ProjectConfigService
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.github.reactunitconverter.settings.ProjectSettingsConfigurable

class SettingsAction : AnAction("Settings...", "Open React Unit Converter settings", AllIcons.General.Settings) {
    override fun actionPerformed(e: AnActionEvent) {
        val project: Project = e.getData(CommonDataKeys.PROJECT) ?: return
        ShowSettingsUtil.getInstance().showSettingsDialog(project, ProjectSettingsConfigurable::class.java)
        // force redetect on open
        runCatching { ProjectConfigService.getInstance(project).redetect() }
    }
}
