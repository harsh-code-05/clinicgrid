package com.harsh.hospitalmanagement.service;

import com.harsh.hospitalmanagement.entity.Doctor;
import com.harsh.hospitalmanagement.entity.DoctorVerificationDocument;
import com.harsh.hospitalmanagement.entity.Specialization;
import com.harsh.hospitalmanagement.entity.User;
import com.harsh.hospitalmanagement.enums.DocumentEventType;
import com.harsh.hospitalmanagement.enums.Role;
import com.harsh.hospitalmanagement.enums.VerificationDocumentKind;
import com.harsh.hospitalmanagement.repository.DoctorRepository;
import com.harsh.hospitalmanagement.repository.DoctorVerificationDocumentEventRepository;
import com.harsh.hospitalmanagement.repository.DoctorVerificationDocumentRepository;
import com.harsh.hospitalmanagement.repository.SpecializationRepository;
import com.harsh.hospitalmanagement.repository.UserRepository;
import com.harsh.hospitalmanagement.service.DoctorVerificationService.DocumentKind;
import com.harsh.hospitalmanagement.storage.DocumentKey;
import com.harsh.hospitalmanagement.storage.DocumentStorage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:retention_scheduler_test;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=PostgreSQL",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.open-in-view=false",
        "spring.sql.init.mode=never",
        "hms.document-storage.purge.enabled=true",
        "hms.document-storage.purge-cron=* * * * * *",
        "hms.document-storage.orphan-grace-period=PT1M"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DoctorVerificationDocumentRetentionSchedulerTest {

    @Autowired private ScheduledTaskHolder scheduledTaskHolder;
    @Autowired private DoctorRepository doctorRepository;
    @Autowired private DoctorVerificationDocumentRepository documentRepository;
    @Autowired private DoctorVerificationDocumentEventRepository eventRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private SpecializationRepository specializationRepository;

    @MockitoBean private DocumentStorage documentStorage;
    @MockitoSpyBean private DoctorVerificationDocumentRetentionWorker retentionWorker;

    @Test
    void cronSchedulerIsEnabledAndAutomaticallyExecutesRetentionPurgeAndOrphanReconciliation() throws Exception {
        assertThat(retentionWorker.isPurgeEnabled()).isTrue();
        assertThat(scheduledTaskHolder.getScheduledTasks())
                .anySatisfy(task -> {
                    assertThat(task.getTask()).isInstanceOf(CronTask.class);
                    assertThat(task.getTask().toString()).contains("DoctorVerificationDocumentRetentionWorker.scheduledPurge");
                });

        var spec = new Specialization();
        spec.setName("sched-spec-" + UUID.randomUUID());
        spec = specializationRepository.save(spec);

        var user = new User();
        user.setEmail("sched-doc-" + UUID.randomUUID() + "@example.invalid");
        user.setRole(Role.DOCTOR);
        user = userRepository.save(user);

        var doctor = new Doctor();
        doctor.setUser(user);
        doctor.setSpecialization(spec);
        doctor = doctorRepository.save(doctor);

        Instant now = Instant.now();
        UUID expiredId = UUID.randomUUID();
        DocumentKey expiredKey = new DocumentKey(doctor.getId(), DocumentKind.ID_PROOF, expiredId);
        DoctorVerificationDocument expiredDoc = new DoctorVerificationDocument(
                expiredId, doctor, VerificationDocumentKind.ID_PROOF, expiredKey.toString(),
                "image/png", 1024, "a".repeat(64), now.minus(Duration.ofDays(35)));
        expiredDoc.retire(now.minus(Duration.ofDays(31)), now.minus(Duration.ofHours(1)));
        documentRepository.saveAndFlush(expiredDoc);

        UUID orphanId = UUID.randomUUID();
        DocumentKey orphanKey = new DocumentKey(doctor.getId(), DocumentKind.QUALIFICATION, orphanId);
        when(documentStorage.listStoredDocuments(any(), anyInt()))
                .thenReturn(new DocumentStorage.DocumentScanPage(
                        List.of(new DocumentStorage.StoredDocument(orphanKey, now.minus(Duration.ofMinutes(10)))),
                        null,
                        false));
        when(documentStorage.exists(orphanKey)).thenReturn(true);
        when(documentStorage.delete(any(DocumentKey.class))).thenReturn(true);

        long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(6);
        while (System.nanoTime() < deadlineNanos) {
            DoctorVerificationDocument refreshed = documentRepository.findById(expiredId).orElseThrow();
            boolean orphanDeleted = false;
            try {
                verify(documentStorage).delete(orphanKey);
                orphanDeleted = true;
            } catch (AssertionError ignored) {
                // Waiting for scheduled tick to run.
            }
            if (refreshed.isPurged() && orphanDeleted) {
                break;
            }
            Thread.sleep(150);
        }

        verify(retentionWorker, atLeastOnce()).scheduledPurge();
        verify(documentStorage).delete(expiredKey);
        verify(documentStorage).delete(orphanKey);
        assertThat(documentRepository.findById(expiredId).orElseThrow().isPurged()).isTrue();
        assertThat(eventRepository.findByDocumentIdOrderByOccurredAtDescIdDesc(expiredId))
                .extracting(e -> e.getEventType())
                .contains(DocumentEventType.DOCUMENT_PURGED);
    }
}
