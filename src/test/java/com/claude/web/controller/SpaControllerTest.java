package com.claude.web.controller;

import org.junit.jupiter.api.Test;
import org.springframework.ui.ConcurrentModel;
import org.springframework.ui.Model;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SpaController 单元测试
 */
class SpaControllerTest {

    @Test
    void testIndex() {
        SpaController controller = new SpaController();
        Model model = new ConcurrentModel();
        String view = controller.index(model);
        assertEquals("index", view);
        assertEquals(true, model.getAttribute("hideDropdowns"));
    }
}
