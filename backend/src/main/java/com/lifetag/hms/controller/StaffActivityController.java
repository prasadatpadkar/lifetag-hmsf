package com.lifetag.hms.controller;

import com.lifetag.hms.entity.StaffActivity;
import com.lifetag.hms.repository.StaffActivityRepository;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/staff-activity")
public class StaffActivityController {

    @Autowired
    private StaffActivityRepository staffActivityRepository;

    @GetMapping
    public List<StaffActivity> getAll() {
        return staffActivityRepository.findAll();
    }

    @PostMapping
    @Transactional
    public StaffActivity create(@Valid @RequestBody StaffActivity activity) {
        return staffActivityRepository.save(activity);
    }
}
