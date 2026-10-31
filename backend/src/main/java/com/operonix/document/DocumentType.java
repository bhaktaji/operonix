package com.operonix.document;

public enum DocumentType {
    QUOTE("Q"),
    JOB("J"),
    INVOICE("INV");

    private final String prefix;

    DocumentType(String prefix) {
        this.prefix = prefix;
    }

    public String prefix() {
        return prefix;
    }
}
