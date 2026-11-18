package com.harsh.hospitalmanagement.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Composite AI Engine for the Patient Assistant.
 * Routes between Cloudflare Workers AI (GLM-4.7-Flash) and the deterministic FaqChatbot.
 */
@Component
public class PatientAiEngine {

    private static final Logger log = LoggerFactory.getLogger(PatientAiEngine.class);

    public static final String SYSTEM_PROMPT = """
            You are the Patient Health Education Assistant for Hospital Management System (HMS), a hospital demonstration application.
            Your role is to provide general health education, explain common hospital services, and assist patients with navigation questions.

            CRITICAL SAFETY INSTRUCTIONS:
            1. DO NOT diagnose medical conditions or interpret symptoms as a clinical diagnosis.
            2. DO NOT prescribe medications, recommend specific drug dosages, or alter existing prescriptions.
            3. DO NOT claim to replace a qualified physician, doctor, or medical consultation.
            4. Make medical uncertainty explicit. When asked about health symptoms or medical concerns, provide general health education and advise consulting a qualified doctor.
            5. In any acute emergency situation (e.g., severe chest pain, shortness of breath, heavy bleeding), instruct the patient immediately to call emergency services or go to the nearest emergency room.
            6. DO NOT invent appointment availability or claim an appointment has been booked. Appointments must be booked through the Book Appointment tab.
            7. Keep your tone professional, supportive, concise, and clear.
            """;

    private final CloudflareAiClient cloudflareClient;
    private final CloudflareAiProperties properties;
    private final FaqChatbot faqChatbot;

    @Value("${hms.ai.provider:FAQ}")
    private String providerSetting;

    public PatientAiEngine(CloudflareAiClient cloudflareClient, CloudflareAiProperties properties, FaqChatbot faqChatbot) {
        this.cloudflareClient = cloudflareClient;
        this.properties = properties;
        this.faqChatbot = faqChatbot;
    }

    public boolean isCloudflareEnabled() {
        return ("CLOUDFLARE".equalsIgnoreCase(providerSetting) || "AUTO".equalsIgnoreCase(providerSetting))
                && properties.isConfigured();
    }

    public AiResponse generateResponse(String userPrompt, String optionalAppointmentContext) {
        if (isCloudflareEnabled()) {
            try {
                String fullPrompt = userPrompt;
                if (optionalAppointmentContext != null && !optionalAppointmentContext.isBlank()) {
                    fullPrompt = "[Patient Confirmed Appointment Summary: " + optionalAppointmentContext + "]\n\n" + userPrompt;
                }
                String llmAnswer = cloudflareClient.generateChatCompletion(SYSTEM_PROMPT, fullPrompt);
                if (llmAnswer != null && !llmAnswer.isBlank()) {
                    return new AiResponse(llmAnswer, AiResponseSource.CLOUDFLARE_LLM);
                }
            } catch (CloudflareAiException ex) {
                log.warn("Cloudflare Workers AI generation failed ({}: {}). Falling back to FAQ.",
                        ex.getReason(), ex.getMessage());
                if (ex.getReason() == CloudflareAiException.Reason.QUOTA_EXCEEDED) {
                    String fallback = faqChatbot.answer(userPrompt);
                    return new AiResponse(
                            fallback + "\n\n(Notice: Cloudflare Workers AI free daily quota has been reached; answered via hospital FAQ knowledge base.)",
                            AiResponseSource.FAQ_FALLBACK);
                }
            } catch (Exception ex) {
                log.warn("Unexpected failure during Cloudflare AI invocation: {}. Falling back to FAQ.", ex.getMessage());
            }
        }

        // Fallback to deterministic FAQ
        String fallbackAnswer = faqChatbot.answer(userPrompt);
        return new AiResponse(fallbackAnswer, AiResponseSource.FAQ_FALLBACK);
    }
}
