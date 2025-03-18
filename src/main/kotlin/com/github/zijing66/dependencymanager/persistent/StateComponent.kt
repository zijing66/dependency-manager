package com.github.zijing66.dependencymanager.persistent

import com.intellij.openapi.components.*

@Service
@State(name = "DependencyState", storages = [Storage(value = "dependencyManagerConfig.xml")])
class StateComponent : SimplePersistentStateComponent<StateComponent.State>(State()) {

    class State : BaseState() {
        var condaInstallationPath by string("")
    }
}