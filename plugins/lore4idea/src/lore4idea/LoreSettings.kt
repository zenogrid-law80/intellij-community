// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package lore4idea

import com.intellij.execution.configurations.PathEnvironmentVariableUtil
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.Project
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel

@Service(Service.Level.PROJECT)
@State(name = "LoreSettings", storages = [Storage("lore.xml")])
internal class LoreSettings : SimplePersistentStateComponent<LoreSettings.State>(State()) {
  class State : BaseState() {
    var executable by string(detectLoreExecutable())
    var timeoutSeconds by property(120)
    var historyLimit by property(200)
    var lastServerUrl by string()
  }
}

@Suppress("UnstableApiUsage")
internal fun detectLoreExecutable(pathVariable: String? = PathEnvironmentVariableUtil.getPathVariableValue()): String {
  return PathEnvironmentVariableUtil.findFirst("lore", pathVariable)?.toString() ?: "lore"
}

internal class LoreConfigurable(private val project: Project) : BoundConfigurable(LoreBundle.message("lore.name")) {
  override fun createPanel() = panel {
    val settings = project.service<LoreSettings>().state
    row(LoreBundle.message("settings.executable")) {
      textField().align(AlignX.FILL).bindText({ settings.executable ?: "lore" }, { settings.executable = it.trim() })
        .validationOnApply { if (it.text.isBlank()) error(LoreBundle.message("settings.executable.required")) else null }
        .comment(LoreBundle.message("settings.executable.help"))
    }
    row(LoreBundle.message("settings.timeout")) { intTextField(1..3600).bindIntText(settings::timeoutSeconds) }
    row(LoreBundle.message("settings.history.limit")) { intTextField(1..10000).bindIntText(settings::historyLimit) }
  }
}
