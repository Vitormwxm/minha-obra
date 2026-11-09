package br.com.minhaobra.controller;

import br.com.minhaobra.dto.Projection;
import br.com.minhaobra.dto.PublicProjection;
import br.com.minhaobra.service.PublicProjectionService;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PublicProjectionController {
    private final PublicProjectionService projection;

    public PublicProjectionController(PublicProjectionService projection) {
        this.projection = projection;
    }

    @PostMapping(path = "/api/public/projection", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Projection.Result> project(@Valid @RequestBody PublicProjection.Input input) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(projection.project(input));
    }
}
