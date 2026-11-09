package br.com.minhaobra.controller;

import br.com.minhaobra.dto.BuilderProjection;
import br.com.minhaobra.service.BuilderProjectionService;
import br.com.minhaobra.service.SessionContextService;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class BuilderProjectionController {
    private final BuilderProjectionService projection;
    private final SessionContextService sessions;

    public BuilderProjectionController(BuilderProjectionService projection, SessionContextService sessions) {
        this.projection = projection;
        this.sessions = sessions;
    }

    @PostMapping("/api/finance/builder-projection")
    public ResponseEntity<BuilderProjection.Result> project(Authentication auth,
            @Valid @RequestBody BuilderProjection.Input input) {
        var result = projection.project(sessions.principal(auth), input);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result);
    }
}
