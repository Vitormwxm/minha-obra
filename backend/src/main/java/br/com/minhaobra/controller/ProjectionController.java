package br.com.minhaobra.controller;

import br.com.minhaobra.dto.Projection;
import br.com.minhaobra.service.ProjectionService;
import br.com.minhaobra.service.SessionContextService;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ProjectionController {
    private final ProjectionService projection;
    private final SessionContextService sessions;

    public ProjectionController(ProjectionService projection, SessionContextService sessions) {
        this.projection = projection;
        this.sessions = sessions;
    }

    @PostMapping("/api/finance/projection")
    public ResponseEntity<Projection.Result> project(Authentication auth, @Valid @RequestBody Projection.Input input) {
        Projection.Result result = projection.project(sessions.principal(auth), input);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result);
    }
}
