package com.smplkit.errors;

/**
 * Thrown when a method requiring connect() is called before connecting.
 */
public class SmplNotConnectedException extends SmplException {
    public SmplNotConnectedException() {
        super("SmplClient is not connected. Call connect() first.", 0, null);
    }

    public SmplNotConnectedException(String message) {
        super(message, 0, null);
    }
}
