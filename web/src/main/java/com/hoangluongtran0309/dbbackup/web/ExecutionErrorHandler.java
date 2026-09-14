package com.hoangluongtran0309.dbbackup.web;

import java.util.NoSuchElementException;

import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.view.RedirectView;

/**
 * Turns "it is not there" into a message on a page the operator can act from,
 * rather than a 500.
 *
 * <p>Anything referenced by a URL can be gone by the time that URL is followed —
 * another tab, or another person. That is ordinary, not exceptional.
 */
@ControllerAdvice
class ExecutionErrorHandler {

    @ExceptionHandler(NoSuchElementException.class)
    RedirectView notFound(NoSuchElementException e, RedirectAttributes flash) {
        flash.addFlashAttribute("error", e.getMessage());
        return new RedirectView("/executions");
    }
}
