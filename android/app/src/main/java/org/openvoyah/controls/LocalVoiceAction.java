package org.openvoyah.controls;

/** A synchronous Android capability. Vehicle transport is a separate adapter. */
interface LocalVoiceAction {
    boolean supports(String command);
    String execute(String command) throws Exception;
}
