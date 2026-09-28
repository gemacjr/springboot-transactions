package com.example.transactions.web;

import com.example.transactions.demo.DemoRunner;
import com.example.transactions.demo.DemoRunner.DemoResult;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Each endpoint runs one pitfall; the boolean flag switches between the buggy and fixed variant. */
@RestController
@RequestMapping("/api/demos")
public class DemoController {

    private final DemoRunner demos;

    public DemoController(DemoRunner demos) {
        this.demos = demos;
    }

    @PostMapping("/checked-exception")
    public DemoResult checkedException(@RequestParam(defaultValue = "false") boolean fixed) {
        return demos.checkedException(fixed);
    }

    @PostMapping("/self-invocation")
    public DemoResult selfInvocation(@RequestParam(defaultValue = "false") boolean fixed) {
        return demos.selfInvocation(fixed);
    }

    @PostMapping("/swallowed-exception")
    public DemoResult swallowedException(@RequestParam(defaultValue = "false") boolean fixed) {
        return demos.swallowedException(fixed);
    }

    @PostMapping("/lost-update")
    public DemoResult lostUpdate(@RequestParam(defaultValue = "false") boolean fixed) {
        return demos.lostUpdate(fixed);
    }

    @PostMapping("/optimistic-lock")
    public DemoResult optimisticLock(@RequestParam(defaultValue = "false") boolean fixed) {
        return demos.optimisticLock(fixed);
    }

    @PostMapping("/propagation")
    public DemoResult propagation() {
        return demos.propagation();
    }

    @PostMapping("/mandatory")
    public DemoResult mandatory() {
        return demos.mandatory();
    }
}
