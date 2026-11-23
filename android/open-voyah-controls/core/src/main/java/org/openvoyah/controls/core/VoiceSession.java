package org.openvoyah.controls.core;

import java.util.function.Consumer;

/** At most one final recognition per session; partial text never executes actions. */
public final class VoiceSession {
    public interface Executor { void execute(VoiceCatalog.Command command, String phrase, Consumer<String> completion); }
    private final VoiceCatalog catalog;
    private final Executor executor;
    private final java.util.function.Function<String,String> messages;
    private long generation;
    private boolean listening;
    public VoiceSession(VoiceCatalog catalog, Executor executor) { this(catalog, executor, java.util.function.Function.identity()); }
    public VoiceSession(VoiceCatalog catalog, Executor executor, java.util.function.Function<String,String> messages) {
        this.catalog = catalog; this.executor = executor; this.messages = messages;
    }
    public synchronized long begin() { listening = true; return ++generation; }
    public synchronized void cancel() { listening = false; generation++; }
    public synchronized void accept(long token, String text, Consumer<String> completion) {
        if (!listening || token != generation) return;
        listening = false;
        String phrase = VoiceCatalog.normalize(text);
        if (phrase.equals("стоп") || phrase.equals("отмена") || phrase.equals("cancel") || phrase.equals("stop")) { completion.accept(messages.apply("Cancelled")); return; }
        VoiceCatalog.Command command = catalog.match(phrase);
        if (command == null) { completion.accept(messages.apply("Unknown command")); return; }
        try {
            executor.execute(command, phrase, message -> {
                synchronized (VoiceSession.this) { if (generation == token) completion.accept(message); }
            });
        } catch (RuntimeException ex) { completion.accept(messages.apply("Command failed") + ": " + ex.getMessage()); }
    }
}
