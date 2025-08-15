package io.lw900925.weibod.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.lw900925.weibod.service.WebService;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * @author LIUWEI
 */
@RestController
public class WebController {

    @Autowired
    private WebService webService;

    @GetMapping("/start")
    public ResponseEntity<Flux<String>> start(@RequestParam String uid, @RequestParam List<String> filters) {
        return ResponseEntity.ok().contentType(MediaType.TEXT_EVENT_STREAM)
                .body(Flux.create(sink -> {
                    try {
                        webService.run(uid, filters, sink);
                        sink.complete();
                    } catch (Exception e) {
                        sink.error(e);
                    }
                }));
    }
}
