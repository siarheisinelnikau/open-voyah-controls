package org.openvoyah.controls.presentation.settings;

import java.util.function.Consumer;
import org.openvoyah.controls.core.PanelControls.Operation;
import org.openvoyah.controls.core.PanelControls.Result;
import org.openvoyah.controls.core.PanelControls;

/** Tracks a single bridge operation across foreground and background transitions. */
public final class PanelViewModel {
    public record State(boolean busy, Result result) { }

    private final PanelControls panel;
    private Consumer<State> observer;
    private State state = new State(false, null);

    public PanelViewModel(PanelControls panel) { this.panel = panel; }

    public String health() { return panel.health(); }
    public boolean configured() { return panel.configured(); }
    public void publish(Consumer<String> error) { panel.publish(error); }

    public void observe(Consumer<State> observer) {
        this.observer = observer;
        emit();
    }

    public void run(Operation operation) {
        if (state.busy()) return;
        state = new State(true, null);
        emit();
        panel.run(operation, result -> {
            state = new State(false, result);
            emit();
        });
    }

    private void emit() {
        if (observer != null) observer.accept(state);
    }
}
