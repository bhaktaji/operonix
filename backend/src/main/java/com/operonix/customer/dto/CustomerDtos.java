package com.operonix.customer.dto;

import com.operonix.customer.Customer;
import com.operonix.customer.ServiceLocation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class CustomerDtos {

    private CustomerDtos() {
    }

    public record Address(
            @Size(max = 160) String line1,
            @Size(max = 160) String line2,
            @Size(max = 80) String city,
            @Size(max = 80) String region,
            @Size(max = 16) String postalCode,
            @Pattern(regexp = "[A-Za-z]{2}", message = "must be a two-letter country code") String country
    ) {
        public static Address from(Customer customer) {
            if (customer.getBillingLine1() == null && customer.getBillingCity() == null) {
                return null;
            }
            return new Address(customer.getBillingLine1(), customer.getBillingLine2(), customer.getBillingCity(),
                    customer.getBillingRegion(), customer.getBillingPostalCode(), customer.getBillingCountry());
        }
    }

    public record LocationRequest(
            @NotBlank @Size(max = 80) String label,
            @NotBlank @Size(max = 160) String line1,
            @Size(max = 160) String line2,
            @NotBlank @Size(max = 80) String city,
            @Size(max = 80) String region,
            @NotBlank @Size(max = 16) String postalCode,
            @NotBlank @Pattern(regexp = "[A-Za-z]{2}", message = "must be a two-letter country code") String country,
            @Size(max = 1000) String accessNotes,
            @Size(max = 64) String timezone,
            boolean isPrimary
    ) {
    }

    public record CreateRequest(
            @NotBlank @Size(max = 160) String name,
            @Size(max = 120) String contactName,
            @Email @Size(max = 254) String email,
            @Pattern(regexp = "^[0-9+() .-]{3,32}$", message = "must be a valid phone number") String phone,
            @Size(max = 2000) String notes,
            @Valid Address billingAddress,
            @Valid LocationRequest primaryLocation
    ) {
    }

    public record UpdateRequest(
            @NotBlank @Size(max = 160) String name,
            @Size(max = 120) String contactName,
            @Email @Size(max = 254) String email,
            @Pattern(regexp = "^[0-9+() .-]{3,32}$", message = "must be a valid phone number") String phone,
            @Size(max = 2000) String notes,
            @Valid Address billingAddress
    ) {
    }

    public record LocationResponse(UUID id, String label, String line1, String line2, String city, String region,
                                   String postalCode, String country, String accessNotes, String timezone,
                                   boolean isPrimary) {

        public static LocationResponse from(ServiceLocation location) {
            return new LocationResponse(location.getId(), location.getLabel(), location.getLine1(),
                    location.getLine2(), location.getCity(), location.getRegion(), location.getPostalCode(),
                    location.getCountry(), location.getAccessNotes(), location.getTimezone(), location.isPrimary());
        }
    }

    public record Summary(UUID id, String name, String contactName, String email, String phone, String status) {

        public static Summary from(Customer customer) {
            return new Summary(customer.getId(), customer.getName(), customer.getContactName(),
                    customer.getEmail(), customer.getPhone(), customer.getStatus().name());
        }
    }

    public record Response(UUID id, String name, String contactName, String email, String phone, String notes,
                           String status, Address billingAddress, List<LocationResponse> locations,
                           long version, Instant createdAt, Instant updatedAt) {

        public static Response from(Customer customer, List<ServiceLocation> locations) {
            return new Response(customer.getId(), customer.getName(), customer.getContactName(),
                    customer.getEmail(), customer.getPhone(), customer.getNotes(), customer.getStatus().name(),
                    Address.from(customer),
                    locations.stream().map(LocationResponse::from).toList(),
                    customer.getVersion(), customer.getCreatedAt(), customer.getUpdatedAt());
        }
    }
}
