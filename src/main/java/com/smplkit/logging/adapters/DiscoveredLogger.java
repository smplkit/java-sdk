package com.smplkit.logging.adapters;

/** A logger discovered by a {@link LoggingAdapter} during runtime scanning. */
public record DiscoveredLogger(String name, String level) {}
