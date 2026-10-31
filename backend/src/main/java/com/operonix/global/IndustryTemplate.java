package com.operonix.global;

import java.util.Arrays;
import java.util.List;

/**
 * Industry profiles are configuration, not code paths. A template changes the vocabulary and the
 * onboarding defaults a workspace starts with; it never changes how quoting, scheduling, invoicing or
 * payments behave. See docs/product-evolution/ARCHITECTURAL_DECISIONS.md (ADR-12).
 */
public enum IndustryTemplate {

    GENERAL_SERVICE("General service business",
            "Field service, maintenance or any business that quotes work and bills for it.",
            "General service"),
    CLEANING("Cleaning & facilities",
            "Residential, commercial or post-construction cleaning teams.",
            "Cleaning service"),
    PEST_CONTROL("Pest control",
            "Inspection, treatment and recurring prevention visits.",
            "Pest treatment"),
    EQUIPMENT_RENTAL("Equipment rental",
            "Rental periods, delivery, collection and damage charges.",
            "Equipment rental"),
    AUTO_REPAIR("Auto repair & maintenance",
            "Workshop jobs, parts, labour hours and vehicle handover.",
            "Repair job"),
    PLUMBING_ELECTRICAL("Plumbing & electrical",
            "Emergency call-outs, installations and compliance certificates.",
            "Site visit"),
    PROPERTY_MAINTENANCE("Property maintenance",
            "Portfolio upkeep, inspections and tenant-reported repairs.",
            "Maintenance visit"),
    CONSTRUCTION("Construction & contracting",
            "Multi-stage projects, subcontractors and progress billing.",
            "Project works"),
    LOGISTICS("Local logistics",
            "Routes, deliveries, collections and proof of delivery.",
            "Delivery run"),
    TRAINING("Training & education",
            "Courses, sessions, attendance and certification.",
            "Training session");

    private final String label;
    private final String description;
    private final String defaultJobTitle;

    IndustryTemplate(String label, String description, String defaultJobTitle) {
        this.label = label;
        this.description = description;
        this.defaultJobTitle = defaultJobTitle;
    }

    public String label() {
        return label;
    }

    public String description() {
        return description;
    }

    /** Used when a job is created without a title, instead of the old hardcoded "Cleaning service". */
    public String defaultJobTitle() {
        return defaultJobTitle;
    }

    public static List<IndustryTemplate> all() {
        return Arrays.asList(values());
    }

    public static IndustryTemplate from(String value) {
        if (value == null || value.isBlank()) {
            return GENERAL_SERVICE;
        }
        String normalised = value.trim().toUpperCase(java.util.Locale.ROOT).replace('-', '_');
        for (IndustryTemplate template : values()) {
            if (template.name().equals(normalised)) {
                return template;
            }
        }
        return null;
    }
}