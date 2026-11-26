package org.openvoyah.controls.presentation.rules

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.openvoyah.controls.R
import org.openvoyah.controls.data.rules.ConditionalRulesPreferences
import org.openvoyah.controls.domain.rules.ConditionalRule
import org.openvoyah.controls.domain.rules.ConditionalRuleLibrary
import org.openvoyah.controls.domain.rules.DoorRequirement
import org.openvoyah.controls.domain.rules.RuleConditions
import org.openvoyah.controls.domain.routines.NamedRoutine
import org.openvoyah.controls.domain.routines.RoutineLibrary
import org.openvoyah.controls.domain.vehicle.automation.VehicleGear

internal data class ConditionalRuleDraft(
    val original: ConditionalRule?,
    val id: String,
    val name: String,
    val routine: NamedRoutine?,
    val gear: VehicleGear? = VehicleGear.PARK,
    val doors: DoorRequirement? = null,
    val enabled: Boolean = false,
    val cooldownSeconds: Int = 30,
    val conditions: RuleConditions = RuleConditions.EMPTY,
) {
    val validName: Boolean get() = NamedRoutine.normalizeName(name) != null
    val hasCondition: Boolean get() = gear != null || doors != null || !conditions.isEmpty
}

internal data class ConditionalRulesForm(
    val draft: ConditionalRuleDraft? = null,
    val saving: Boolean = false,
    val error: Int? = null,
)

internal data class ConditionalRulesUiState(
    val library: ConditionalRuleLibrary? = null,
    val routines: RoutineLibrary? = null,
    val loadFailed: Boolean = false,
    val routinesFailed: Boolean = false,
    val form: ConditionalRulesForm = ConditionalRulesForm(),
)

internal class ConditionalRulesViewModel(
    private val store: ConditionalRulesPreferences,
    routines: Flow<Result<RoutineLibrary>>,
) : ViewModel() {
    private val form = MutableStateFlow(ConditionalRulesForm())
    val state = combine(store.observe(), routines, form) { rules, library, editor ->
        ConditionalRulesUiState(rules.getOrNull(), library.getOrNull(), rules.isFailure, library.isFailure, editor)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(replayExpirationMillis = 0), ConditionalRulesUiState())

    fun create() {
        val current = state.value
        if (current.library == null || current.library.entries.size >= ConditionalRuleLibrary.MAX_RULES ||
            current.routines == null || current.routines.entries.isEmpty() || form.value.saving
        ) return
        form.value = ConditionalRulesForm(draft = ConditionalRuleDraft(null, UUID.randomUUID().toString(), "",
            current.routines.entries.first()))
    }

    fun edit(rule: ConditionalRule) {
        if (form.value.saving || state.value.library?.entries?.firstOrNull { it.id == rule.id } != rule) return
        form.value = ConditionalRulesForm(draft = ConditionalRuleDraft(rule, rule.id, rule.name, rule.routine,
            rule.gear, rule.doors, rule.enabled, rule.cooldownSeconds, rule.conditions))
    }

    fun closeEditor() { if (!form.value.saving) form.value = ConditionalRulesForm() }
    fun name(value: String) = changeDraft { copy(name = value) }
    fun gear(value: VehicleGear?) = changeDraft { copy(gear = value) }
    fun doors(value: DoorRequirement?) = changeDraft { copy(doors = value) }
    fun conditions(value: RuleConditions) = changeDraft { copy(conditions = value) }
    fun enabled(value: Boolean) = changeDraft { copy(enabled = value) }
    fun cooldown(value: Int) = changeDraft {
        if (value in ConditionalRule.MIN_COOLDOWN_SECONDS..ConditionalRule.MAX_COOLDOWN_SECONDS) copy(cooldownSeconds = value) else this
    }
    fun routine(value: NamedRoutine) = changeDraft {
        if (state.value.routines?.entries?.firstOrNull { it.id == value.id } == value) copy(routine = value) else this
    }

    private fun changeDraft(change: ConditionalRuleDraft.() -> ConditionalRuleDraft) = form.update {
        if (it.saving) it else it.copy(draft = it.draft?.change(), error = null)
    }

    fun save() {
        val draft = form.value.draft ?: return
        val name = NamedRoutine.normalizeName(draft.name) ?: return
        val routine = draft.routine ?: return
        if (!draft.hasCondition || state.value.routines?.entries?.firstOrNull { it.id == routine.id } != routine) {
            form.update { it.copy(error = R.string.rules_routine_changed) }
            return
        }
        val next = ConditionalRule(draft.id, name, routine, draft.gear, draft.doors, draft.enabled, draft.cooldownSeconds, draft.conditions)
        change { it.save(draft.original, next) }
    }

    fun remove() {
        val original = form.value.draft?.original ?: return
        change { it.remove(original) }
    }

    fun setEnabled(rule: ConditionalRule, enabled: Boolean) {
        if (enabled && state.value.routines?.let { rule.currentRoutine(it) } == null) {
            form.update { it.copy(error = R.string.rules_routine_changed) }
            return
        }
        change { it.save(rule, rule.copy(enabled = enabled)) }
    }

    private fun change(transform: (ConditionalRuleLibrary) -> ConditionalRuleLibrary) {
        val before = form.value
        if (state.value.library == null || before.saving || !form.compareAndSet(before, before.copy(saving = true, error = null))) return
        viewModelScope.launch {
            try {
                store.update(transform)
                form.value = ConditionalRulesForm()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                form.value = before.copy(saving = false, error = R.string.rules_save_failed)
            }
        }
    }
}
