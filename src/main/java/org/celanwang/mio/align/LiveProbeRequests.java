package org.celanwang.mio.align;

import org.celanwang.mio.goals.JdbcGoalPrograms;
import org.celanwang.mio.memory.PersonalContext;
import org.celanwang.mio.orchestration.CoordinationStore;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

@Component
public final class LiveProbeRequests implements ProbeRequests {
    private final PreferenceCapture capture;
    private final Function<String, GoalView> goals;
    private final SkillRedlineCatalog skills;

    public LiveProbeRequests(PersonalContext personal, CoordinationStore goals, JdbcGoalPrograms programs,
                             SkillRedlineCatalog skills) {
        this((session, domains, intent) -> personal.capture(session, domains, intent),
                session -> load(goals, programs, session), skills);
    }

    LiveProbeRequests(PreferenceCapture capture, Function<String, GoalView> goals, SkillRedlineCatalog skills) {
        this.capture = capture;
        this.goals = goals;
        this.skills = skills;
    }

    @Override
    public ProbeRequest forCall(String sessionId, String callId, String toolName, Map<String, Object> input,
                                boolean readOnlyDeclared, String intent) {
        String domain = ToolDomains.of(toolName);
        if (skills == null || !skills.available()) {
            return ProbeRequest.unavailable(sessionId, callId, toolName, input, readOnlyDeclared, domain);
        }
        Map<String, Object> captured;
        try {
            captured = capture.capture(sessionId, domain == null ? Set.of() : Set.of(domain), intent);
        } catch (RuntimeException failure) {
            return ProbeRequest.unavailable(sessionId, callId, toolName, input, readOnlyDeclared, domain);
        }
        PersonalContextSnapshot prefs = PersonalContextSnapshot.fromCapture(captured);
        if (!prefs.available()) {
            return ProbeRequest.unavailable(sessionId, callId, toolName, input, readOnlyDeclared, domain);
        }
        GoalView goal;
        try {
            goal = goals.apply(sessionId);
        } catch (RuntimeException failure) {
            return ProbeRequest.unavailable(sessionId, callId, toolName, input, readOnlyDeclared, domain);
        }
        if (goal == null) {
            return ProbeRequest.unavailable(sessionId, callId, toolName, input, readOnlyDeclared, domain);
        }
        return new ProbeRequest(sessionId, callId, toolName, input, readOnlyDeclared, domain, goal.id,
                prefs, goal.constraints, skills.forDomain(domain));
    }

    private static GoalView load(CoordinationStore store, JdbcGoalPrograms programs, String session) {
        Optional<org.celanwang.mio.orchestration.GoalRecord> goal = store.goal(session);
        if (goal.isEmpty()) {
            return GoalView.none();
        }
        List<HardConstraint> constraints = new ArrayList<>();
        programs.find(goal.get().id()).ifPresent(program -> {
            program.fields().forEach((key, field) -> constraints.add(new HardConstraint("field:" + key, key + "=" + field.value())));
            for (var step : program.steps()) {
                for (var check : step.checks()) {
                    constraints.add(new HardConstraint(step.id() + ":" + check.fieldId(),
                            check.fieldId() + " " + check.operator().name() + " " + check.expected()));
                }
            }
        });
        return new GoalView(goal.get().id(), List.copyOf(constraints));
    }

    @FunctionalInterface
    interface PreferenceCapture {
        Map<String, Object> capture(String session, Set<String> domains, String intent);
    }

    record GoalView(String id, List<HardConstraint> constraints) {
        static GoalView none() {
            return new GoalView(null, List.of());
        }
    }
}
