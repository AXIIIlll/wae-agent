package com.wae.agent.core.omega

/**
 * WAE-Ω 第 2 層：App Model 編譯器（AppModelCompiler）。
 *
 * 把「觀察到的畫面/動作」編譯成 [AppModel]：
 *  - 每個觀察 = 一個畫面狀態（指紋＋出現的 UI 型別）＋若干動作結果（轉移）
 *  - 同一個 App 多次觀察 → 合併、累加成功/失敗次數、版本遞增
 *  - 模型本身只含結構（不含文字內容），可離線分享
 *
 * 這是「主動探索」的落地點：探索器只要把每一步的觀察餵進來，
 * 模型就會越長越完整（而不是像 automaton 只記導覽邊）。
 *
 * 純 Kotlin，JVM 可測。
 */
object AppModelCompiler {

    /** 一次觀察：一個畫面狀態 + 從它出發的動作結果 */
    data class Observation(
        val fingerprints: List<Long> = emptyList(),
        val types: Set<UiOntology.UiType> = emptySet(),
        val fields: List<FieldSchema> = emptyList(),
        val risks: List<RiskMark> = emptyList(),
        val transitions: List<Transition> = emptyList()
    )

    /** 同一個 pkg 的多個觀察 → 一個 AppModel */
    fun compile(pkg: String, observations: List<Observation>, version: Int = 1): AppModel {
        if (pkg.isBlank() || observations.isEmpty()) return AppModel(pkg = pkg, version = version)
        val states = LinkedHashMap<String, AppState>()
        val transitions = LinkedHashMap<String, Transition>()
        val fields = LinkedHashMap<String, FieldSchema>()
        val risks = LinkedHashMap<String, RiskMark>()

        for (obs in observations) {
            val stateId = stateIdOf(obs.fingerprints, states.size)
            val prev = states[stateId]
            states[stateId] = AppState(
                id = stateId,
                fingerprints = (prev?.fingerprints ?: emptyList()) + obs.fingerprints,
                types = (prev?.types ?: emptySet()) + obs.types
            )
            for (t in obs.transitions) {
                val fixed = if (t.fromId.isBlank()) t.copy(fromId = stateId) else t
                val key = keyOf(fixed)
                val ex = transitions[key]
                transitions[key] = if (ex == null) fixed else ex.copy(
                    ok = ex.ok + fixed.ok,
                    fail = ex.fail + fixed.fail
                )
            }
            for (f in obs.fields) fields[f.name] = f
            for (r in obs.risks) risks[r.kind.name + "|" + r.selector] = r
        }

        return AppModel(
            pkg = pkg,
            version = version,
            states = states.values.toList(),
            transitions = transitions.values.toList(),
            fields = fields.values.toList(),
            risks = risks.values.toList()
        ).withComputedHash()
    }

    /** 增量合併：把一次新觀察併進現有模型（版本 +1） */
    fun merge(existing: AppModel, observation: Observation): AppModel {
        val version = existing.version + 1
        val combined = accumulateObservation(existing, observation)
        return AppModel(
            pkg = existing.pkg,
            version = version,
            states = combined.states,
            transitions = combined.transitions,
            fields = combined.fields,
            risks = combined.risks
        ).withComputedHash()
    }

    /** 把多個模型合併（例如載入社群分享的模型 + 本機模型） */
    fun mergeModels(base: AppModel, incoming: AppModel): AppModel {
        if (base.pkg != incoming.pkg) return base
        val states = LinkedHashMap<String, AppState>()
        base.states.forEach { states[it.id] = it }
        incoming.states.forEach { s ->
            val prev = states[s.id]
            states[s.id] = AppState(
                id = s.id,
                fingerprints = (prev?.fingerprints ?: emptyList()) + s.fingerprints,
                types = (prev?.types ?: emptySet()) + s.types
            )
        }
        val transitions = LinkedHashMap<String, Transition>()
        base.transitions.forEach { transitions[keyOf(it)] = it }
        incoming.transitions.forEach { t ->
            val key = keyOf(t)
            val ex = transitions[key]
            transitions[key] = if (ex == null) t else ex.copy(ok = ex.ok + t.ok, fail = ex.fail + t.fail)
        }
        val fields = LinkedHashMap<String, FieldSchema>()
        base.fields.forEach { fields[it.name] = it }
        incoming.fields.forEach { fields[it.name] = it }
        val risks = LinkedHashMap<String, RiskMark>()
        base.risks.forEach { risks[it.kind.name + "|" + it.selector] = it }
        incoming.risks.forEach { risks[it.kind.name + "|" + it.selector] = it }
        return AppModel(
            pkg = base.pkg,
            version = maxOf(base.version, incoming.version) + 1,
            states = states.values.toList(),
            transitions = transitions.values.toList(),
            fields = fields.values.toList(),
            risks = risks.values.toList()
        ).withComputedHash()
    }

    private fun accumulateObservation(existing: AppModel, obs: Observation): AppModel {
        val stateId = if (obs.fingerprints.isEmpty()) "s${existing.states.size}" else
            stateIdOf(obs.fingerprints, existing.states.size)
        val states = LinkedHashMap<String, AppState>()
        existing.states.forEach { states[it.id] = it }
        val prev = states[stateId]
        states[stateId] = AppState(
            id = stateId,
            fingerprints = (prev?.fingerprints ?: emptyList()) + obs.fingerprints,
            types = (prev?.types ?: emptySet()) + obs.types
        )
        val transitions = LinkedHashMap<String, Transition>()
        existing.transitions.forEach { transitions[keyOf(it)] = it }
        for (t in obs.transitions) {
            val fixed = if (t.fromId.isBlank()) t.copy(fromId = stateId) else t
            val key = keyOf(fixed)
            val ex = transitions[key]
            transitions[key] = if (ex == null) fixed else ex.copy(
                ok = ex.ok + fixed.ok,
                fail = ex.fail + fixed.fail
            )
        }
        val fields = LinkedHashMap<String, FieldSchema>()
        existing.fields.forEach { fields[it.name] = it }
        obs.fields.forEach { fields[it.name] = it }
        val risks = LinkedHashMap<String, RiskMark>()
        existing.risks.forEach { risks[it.kind.name + "|" + it.selector] = it }
        obs.risks.forEach { risks[it.kind.name + "|" + it.selector] = it }
        return AppModel(
            pkg = existing.pkg,
            version = existing.version,
            states = states.values.toList(),
            transitions = transitions.values.toList(),
            fields = fields.values.toList(),
            risks = risks.values.toList()
        )
    }

    /**
     * 由指紋決定狀態 id：與既有狀態有指紋重疊就沿用該 id，
     * 否則用 `s<序號>`（避免同一畫面被拆成很多狀態）。
     */
    fun stateIdOf(fingerprints: List<Long>, existingCount: Int): String {
        if (fingerprints.isEmpty()) return "s$existingCount"
        return "s" + fingerprints.sorted().joinToString("_").hashCode().toUInt().toString(16).take(8)
    }

    private fun keyOf(t: Transition): String =
        t.fromId + "|" + t.actionKind.name + "|" + (t.targetType?.name ?: "") + "|" +
            t.selector + "|" + t.toId
}
