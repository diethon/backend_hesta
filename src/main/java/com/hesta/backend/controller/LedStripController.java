package com.hesta.backend.controller;


import com.hesta.backend.service.LedStripService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/devices/led-strip")
public class LedStripController {

    private final LedStripService ledStripService;

    public LedStripController(
            LedStripService ledStripService
    ) {
        this.ledStripService = ledStripService;
    }

    @PostMapping("/power/on")
    public ResponseEntity<Void> powerOn() {

        ledStripService.powerOn();

        return ResponseEntity.ok().build();
    }

    @PostMapping("/power/off")
    public ResponseEntity<Void> powerOff() {

        ledStripService.powerOff();

        return ResponseEntity.ok().build();
    }

    @PostMapping("/rgb")
    public ResponseEntity<Void> setRgb(
            @RequestParam int r,
            @RequestParam int g,
            @RequestParam int b
    ) {

        ledStripService.setRgb(r, g, b);

        return ResponseEntity.ok().build();
    }

    @PostMapping("/brightness")
    public ResponseEntity<Void> setBrightness(
            @RequestParam int brightness
    ) {

        ledStripService.setBrightness(brightness);

        return ResponseEntity.ok().build();
    }
}