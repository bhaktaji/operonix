package com.operonix.common.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.util.Set;

public class StrongPasswordValidator implements ConstraintValidator<StrongPassword, String> {

    private static final Set<String> REJECTED = Set.of("password1234", "123456789012", "qwertyuiop12",
            "changeme1234", "passwordpassword");

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null || value.length() < 12 || value.length() > 72) {
            return false;
        }
        boolean letter = value.chars().anyMatch(Character::isLetter);
        boolean digit = value.chars().anyMatch(Character::isDigit);
        return letter && digit && !REJECTED.contains(value.toLowerCase());
    }
}
