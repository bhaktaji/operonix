package com.operonix.identity;

import com.operonix.identity.dto.CreateUserRequest;
import com.operonix.identity.dto.TeamMemberResponse;
import com.operonix.identity.dto.UpdateUserRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/team")
public class TeamController {

    private final TeamService teamService;

    public TeamController(TeamService teamService) {
        this.teamService = teamService;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('OWNER','ADMIN')")
    public List<TeamMemberResponse> list() {
        return teamService.list();
    }

    /**
     * Minimal staff list for assignment pickers. Managers schedule crews but must not see the
     * team administration data (emails, roles, last sign-in) exposed by {@link #list()}.
     */
    @GetMapping("/assignable")
    @PreAuthorize("hasAnyRole('OWNER','ADMIN','MANAGER')")
    public List<TeamMemberResponse.Assignable> assignable() {
        return teamService.assignable();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('OWNER','ADMIN')")
    public TeamMemberResponse create(@Valid @RequestBody CreateUserRequest request) {
        return teamService.create(request);
    }

    @PutMapping("/{userId}")
    @PreAuthorize("hasAnyRole('OWNER','ADMIN')")
    public TeamMemberResponse update(@PathVariable UUID userId,
                                    @Valid @RequestBody UpdateUserRequest request) {
        return teamService.update(userId, request);
    }
}
