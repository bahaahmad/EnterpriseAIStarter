package com.cuenterprise.ai.rag.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatControllerTest {

    @Test
    void detectsCitationOnlyAnswers() {
        assertTrue(ChatController.isCitationOnly("[1]"));
        assertTrue(ChatController.isCitationOnly("  [1] [2] "));
        assertTrue(ChatController.isCitationOnly("[1, 2]."));
        assertTrue(ChatController.isCitationOnly(""));
        assertTrue(ChatController.isCitationOnly(null));
    }

    @Test
    void acceptsRealAnswers() {
        assertFalse(ChatController.isCitationOnly("Agents may approve up to AED 500 [1]."));
        assertFalse(ChatController.isCitationOnly("Within 7 business days."));
    }
}
