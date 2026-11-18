package com.harsh.hospitalmanagement.ai;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PatientAiEngineTest {

    @Mock
    private CloudflareAiClient cloudflareClient;

    @Mock
    private FaqChatbot faqChatbot;

    private CloudflareAiProperties properties;
    private PatientAiEngine engine;

    @BeforeEach
    void setUp() {
        properties = new CloudflareAiProperties();
        engine = new PatientAiEngine(cloudflareClient, properties, faqChatbot);
    }

    @Test
    void test01_fallbackToFaqWhenCloudflareDisabled() {
        ReflectionTestUtils.setField(engine, "providerSetting", "FAQ");
        when(faqChatbot.answer("How do I book?")).thenReturn("Open Book Appointment tab.");

        AiResponse response = engine.generateResponse("How do I book?", null);

        assertThat(response.source()).isEqualTo(AiResponseSource.FAQ_FALLBACK);
        assertThat(response.content()).isEqualTo("Open Book Appointment tab.");
        verifyNoInteractions(cloudflareClient);
    }

    @Test
    void test02_successfulCloudflareGeneration() {
        ReflectionTestUtils.setField(engine, "providerSetting", "CLOUDFLARE");
        properties.setAccountId("cf-account-id-12345");
        properties.setApiToken("cf-token-secret-67890");

        when(cloudflareClient.generateChatCompletion(eq(PatientAiEngine.SYSTEM_PROMPT), eq("What are symptoms of flu?")))
                .thenReturn("Flu symptoms typically include fever, chills, and cough. Please consult a doctor.");

        AiResponse response = engine.generateResponse("What are symptoms of flu?", null);

        assertThat(response.source()).isEqualTo(AiResponseSource.CLOUDFLARE_LLM);
        assertThat(response.content()).contains("Flu symptoms typically include fever");
        verifyNoInteractions(faqChatbot);
    }

    @Test
    void test03_quotaExhaustionFallsBackToFaqWithNotice() {
        ReflectionTestUtils.setField(engine, "providerSetting", "CLOUDFLARE");
        properties.setAccountId("cf-account-id-12345");
        properties.setApiToken("cf-token-secret-67890");

        when(cloudflareClient.generateChatCompletion(anyString(), anyString()))
                .thenThrow(new CloudflareAiException(CloudflareAiException.Reason.QUOTA_EXCEEDED, "Daily limit reached", 429));
        when(faqChatbot.answer("How do I cancel?")).thenReturn("Open My Appointments to cancel.");

        AiResponse response = engine.generateResponse("How do I cancel?", null);

        assertThat(response.source()).isEqualTo(AiResponseSource.FAQ_FALLBACK);
        assertThat(response.content()).contains("Open My Appointments to cancel.");
        assertThat(response.content()).contains("Notice: Cloudflare Workers AI free daily quota has been reached; answered via hospital FAQ knowledge base.");
    }

    @Test
    void test04_genericErrorFallsBackToFaqWithoutNotice() {
        ReflectionTestUtils.setField(engine, "providerSetting", "CLOUDFLARE");
        properties.setAccountId("cf-account-id-12345");
        properties.setApiToken("cf-token-secret-67890");

        when(cloudflareClient.generateChatCompletion(anyString(), anyString()))
                .thenThrow(new CloudflareAiException(CloudflareAiException.Reason.SERVER_ERROR, "Model temporarily unavailable", 503));
        when(faqChatbot.answer("Visiting hours?")).thenReturn("Visiting hours are 10 AM to 7 PM.");

        AiResponse response = engine.generateResponse("Visiting hours?", null);

        assertThat(response.source()).isEqualTo(AiResponseSource.FAQ_FALLBACK);
        assertThat(response.content()).isEqualTo("Visiting hours are 10 AM to 7 PM.");
    }

    @Test
    void test05_appointmentContextPrepend() {
        ReflectionTestUtils.setField(engine, "providerSetting", "CLOUDFLARE");
        properties.setAccountId("cf-account-id-12345");
        properties.setApiToken("cf-token-secret-67890");

        ArgumentCaptor<String> userPromptCaptor = ArgumentCaptor.forClass(String.class);
        when(cloudflareClient.generateChatCompletion(eq(PatientAiEngine.SYSTEM_PROMPT), userPromptCaptor.capture()))
                .thenReturn("I see your appointment is scheduled for tomorrow.");

        String appointmentContext = "Department: Cardiology, Date: 2026-11-20, Time: 10:00, Status: CONFIRMED";
        AiResponse response = engine.generateResponse("Can I eat before my visit?", appointmentContext);

        assertThat(response.source()).isEqualTo(AiResponseSource.CLOUDFLARE_LLM);
        String capturedPrompt = userPromptCaptor.getValue();
        assertThat(capturedPrompt).contains("[Patient Confirmed Appointment Summary: Department: Cardiology, Date: 2026-11-20, Time: 10:00, Status: CONFIRMED]");
        assertThat(capturedPrompt).contains("Can I eat before my visit?");
    }
}
