package com.harsh.hospitalmanagement.config;

import com.harsh.hospitalmanagement.enums.VerificationDocumentKind;
import com.harsh.hospitalmanagement.exception.BadRequestException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VerificationDocumentUploadRouteTest {

    @Test
    void matchesCanonicalAliasesContextPathLeadingZerosEncodedDigitsMatrixParamsAndMaxLong() {
        assertMatch("POST", null, "/api/doctors/1/verification/documents/id-proof",
                1L, VerificationDocumentKind.ID_PROOF);
        assertMatch("POST", null, "/api/doctors/42/verification/documents/id_proof",
                42L, VerificationDocumentKind.ID_PROOF);
        assertMatch("POST", null, "/api/doctors/42/verification/documents/ID_PROOF",
                42L, VerificationDocumentKind.ID_PROOF);
        assertMatch("POST", null, "/api/doctors/" + Long.MAX_VALUE + "/verification/documents/QUALIFICATION",
                Long.MAX_VALUE, VerificationDocumentKind.QUALIFICATION);
        assertMatch("POST", null, "/api/doctors/0042/verification/documents/qualification",
                42L, VerificationDocumentKind.QUALIFICATION);
        assertMatch("POST", null, "/api/doctors/%34%32/verification/documents/id-proof",
                42L, VerificationDocumentKind.ID_PROOF);
        assertMatch("POST", "/hms", "/hms/api/doctors/%30%30%34%32/verification/documents/id%2Dproof",
                42L, VerificationDocumentKind.ID_PROOF);
        assertMatch("POST", "/hms", "/hms/api/doctors/42/verification/documents/id%5Fproof",
                42L, VerificationDocumentKind.ID_PROOF);
        assertMatch("POST", "/hms", "/hms/api/doctors/42;v=1/verification/documents/id-proof;k=1",
                42L, VerificationDocumentKind.ID_PROOF);
        assertMatch("POST", "/hms", "/hms/api/doctors/0042;v=1/verification/documents/qualification;k=1",
                42L, VerificationDocumentKind.QUALIFICATION);
    }

    @Test
    void rejectsNonPostPlusSignZeroNegativeWhitespaceOverflowEncodedAndInvalidRoutes() {
        assertNoMatch("GET", null, "/api/doctors/42/verification/documents/id-proof");
        assertNoMatch("DELETE", null, "/api/doctors/42/verification/documents/id-proof");
        assertNoMatch("POST", null, "/api/doctors/0/verification/documents/id-proof");
        assertNoMatch("POST", null, "/api/doctors/000/verification/documents/id-proof");
        assertNoMatch("POST", null, "/api/doctors/+42/verification/documents/id-proof");
        assertNoMatch("POST", null, "/api/doctors/%2B42/verification/documents/id-proof");
        assertNoMatch("POST", null, "/api/doctors/+42;v=1/verification/documents/id-proof");
        assertNoMatch("POST", null, "/api/doctors/0;v=1/verification/documents/id-proof");
        assertNoMatch("POST", null, "/api/doctors/-42/verification/documents/id-proof");
        assertNoMatch("POST", null, "/api/doctors/%2D42/verification/documents/id-proof");
        assertNoMatch("POST", null, "/api/doctors/%2042/verification/documents/id-proof");
        assertNoMatch("POST", null, "/api/doctors/42%20/verification/documents/id-proof");
        assertNoMatch("POST", null, "/api/doctors/4%092/verification/documents/id-proof");
        assertNoMatch("POST", null, "/api/doctors/9223372036854775808/verification/documents/id-proof");
        assertNoMatch("POST", null, "/api/doctors/99999999999999999999999/verification/documents/id-proof");
        assertNoMatch("POST", null, "/api/doctors/%2534%2532/verification/documents/id-proof");
        assertNoMatch("POST", null, "/api/doctors/%D9%A4%D9%A2/verification/documents/id-proof");
        assertNoMatch("POST", null, "/api/doctors/abc/verification/documents/id-proof");
        assertNoMatch("POST", null, "/api/doctors/42/verification/documents/passport");
        assertNoMatch("POST", null, "/api/doctors/42/verification/documents/id%252Dproof");
        assertNoMatch("POST", "/hms", "/api/doctors/42/verification/documents/id-proof");
        assertNoMatch("POST", null, "/api/doctors/42/verification/documents/");
    }

    @Test
    void sharedDoctorIdParserRejectsNonCanonicalNumericFormsUniformly() {
        assertThat(VerificationDocumentUploadRoute.parsePositiveDoctorId("42")).isEqualTo(42L);
        assertThat(VerificationDocumentUploadRoute.parsePositiveDoctorId("0042")).isEqualTo(42L);
        assertThat(VerificationDocumentUploadRoute.parsePositiveDoctorId(Long.toString(Long.MAX_VALUE)))
                .isEqualTo(Long.MAX_VALUE);

        String[] rejected = {
                null, "", "0", "000", "-1", "-42", "+42", "+1",
                " 42", "42 ", "4 2", "\t42", "42\n",
                "9223372036854775808", "99999999999999999999999",
                "\u0664\u0662", "42;v=1", "abc", "%34%32"
        };
        for (String candidate : rejected) {
            assertThat(VerificationDocumentUploadRoute.tryParsePositiveDoctorId(candidate))
                    .as("tryParsePositiveDoctorId(%s)", candidate)
                    .isEmpty();
            assertThatThrownBy(() -> VerificationDocumentUploadRoute.parsePositiveDoctorId(candidate))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessage("Doctor ID must be a positive integer");
        }
    }

    private static void assertMatch(
            String method, String contextPath, String requestUri, long expectedDoctorId, VerificationDocumentKind expectedKind) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, requestUri);
        if (contextPath != null) {
            request.setContextPath(contextPath);
        }
        var match = VerificationDocumentUploadRoute.matchUpload(request);
        assertThat(match).isPresent();
        assertThat(match.get().doctorId()).isEqualTo(expectedDoctorId);
        assertThat(match.get().kind()).isEqualTo(expectedKind);
    }

    private static void assertNoMatch(String method, String contextPath, String requestUri) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, requestUri);
        if (contextPath != null) {
            request.setContextPath(contextPath);
        }
        assertThat(VerificationDocumentUploadRoute.matchUpload(request)).isEmpty();
    }
}
