package com.harsh.hospitalmanagement.service;

import com.harsh.hospitalmanagement.dto.LocationSuggestion;
import com.harsh.hospitalmanagement.exception.BadRequestException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.net.SocketTimeoutException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.harsh.hospitalmanagement.dto.LocationSuggestion.Source.GOOGLE_GEOCODING;
import static com.harsh.hospitalmanagement.dto.LocationSuggestion.Source.GOOGLE_PLACES;
import static com.harsh.hospitalmanagement.dto.LocationSuggestion.Type.AREA;
import static com.harsh.hospitalmanagement.dto.LocationSuggestion.Type.CITY;
import static com.harsh.hospitalmanagement.dto.LocationSuggestion.Type.DISTRICT;
import static com.harsh.hospitalmanagement.service.LocationSearchService.GoogleStatus.AVAILABLE;
import static com.harsh.hospitalmanagement.service.LocationSearchService.GoogleStatus.DISABLED;
import static com.harsh.hospitalmanagement.service.LocationSearchService.GoogleStatus.UNAVAILABLE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class LocationSearchServiceTest {
    private static final String AUTOCOMPLETE = "https://places.googleapis.com/v1/places:autocomplete";
    private static final String TOKEN = "7a0b3948-1abc-4a11-812e-5537b3ad0861";
    private static final String EMPTY = "{\"suggestions\":[]}";
    private static final String MUMBAI_AREA = """
            {"suggestions":[{"placePrediction":{
              "placeId":"central_id","types":["sublocality_level_1","sublocality","political"],
              "structuredFormat":{"mainText":{"text":"Mumbai Central"},
                                  "secondaryText":{"text":"Mumbai, Maharashtra, India"}}
            }}]}
            """;
    private static final String IN_ADDRESS = """
            {"longText":"Maharashtra","shortText":"MH","types":["administrative_area_level_1"]},
            {"longText":"India","shortText":"IN","types":["country"]},
            {"longText":"Mumbai","types":["locality"]}
            """;
    private RestClient client;
    private MockRestServiceServer server;
    private LocationSearchService locations;

    @BeforeEach
    void setUp() {
        var builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).ignoreExpectOrder(true).build();
        client = builder.build();
        locations = new LocationSearchService(new LgdDistrictDirectory(), new GooglePlacesAutocomplete("synthetic-key", client));
    }

    @ParameterizedTest
    @CsvSource({"mum,Mumbai,Maharashtra,maharashtra", "kan,Kanpur,Uttar Pradesh,Uttar Pradesh",
                "luc,Lucknow,Uttar Pradesh,Uttar Pradesh", "del,Delhi,Delhi,India"})
    void returnsCityFromIndiaOnlySessionRequestsWithNoDatabase(String input, String city, String state, String secondary) {
        expectAutocomplete(input, "(cities)").andRespond(withSuccess("""
                {"suggestions":[{"placePrediction":{"placeId":"city_id","types":["locality"],
                   "structuredFormat":{"mainText":{"text":"%s"},"secondaryText":{"text":"%s"}}}}]}
                """.formatted(city, secondary), MediaType.APPLICATION_JSON));
        expectAutocomplete(input, "(regions)").andRespond(withSuccess(EMPTY, MediaType.APPLICATION_JSON));

        var result = locations.search(input, TOKEN);

        assertThat(result.googleStatus()).isEqualTo(AVAILABLE);
        assertThat(result.suggestions()).contains(new LocationSuggestion("city_id", city, state, null,
                CITY, GOOGLE_PLACES, "city_id"));
        server.verify();
    }

    @Test
    void keepsDistrictAndCityDistinctWhileDeduplicatingCityAndAreaPredictions() {
        expectAutocomplete("mum", "(cities)").andRespond(withSuccess("""
                {"suggestions":[
                  {"placePrediction":{"placeId":"mumbai_id","types":["locality"],
                    "structuredFormat":{"mainText":{"text":"Mumbai"},"secondaryText":{"text":"Maharashtra, India"}}}},
                  {"placePrediction":{"placeId":"dubai_id","types":["locality"],
                    "structuredFormat":{"mainText":{"text":"Dubai"},"secondaryText":{"text":"United Arab Emirates"}}}}
                ]}
                """, MediaType.APPLICATION_JSON));
        expectAutocomplete("mum", "(regions)").andRespond(withSuccess("""
                {"suggestions":[
                  {"placePrediction":{"placeId":"mumbai_id","types":["locality"],
                    "structuredFormat":{"mainText":{"text":"Mumbai"},"secondaryText":{"text":"Maharashtra, India"}}}},
                  {"placePrediction":{"placeId":"duplicate_name","types":["locality"],
                    "structuredFormat":{"mainText":{"text":" MUMBAI "},"secondaryText":{"text":"Maharashtra, India"}}}},
                  {"placePrediction":{"placeId":"central_id","types":["sublocality"],
                    "structuredFormat":{"mainText":{"text":"Mumbai Central"},
                                        "secondaryText":{"text":"Mumbai, Maharashtra, India"}}}},
                  {"placePrediction":{"placeId":"postal_id","types":["postal_code"],
                    "structuredFormat":{"mainText":{"text":"400001"},
                                        "secondaryText":{"text":"Mumbai, Maharashtra, India"}}}}
                ]}
                """, MediaType.APPLICATION_JSON));

        var results = locations.search("mum", TOKEN).suggestions();

        assertThat(results.stream().filter(location -> location.type() == CITY).map(LocationSuggestion::name))
                .containsExactly("Mumbai");
        assertThat(results).contains(new LocationSuggestion("central_id", "Mumbai Central", "Maharashtra",
                "Mumbai", AREA, GOOGLE_PLACES, "central_id"));
        assertThat(results).anyMatch(location -> location.type() == DISTRICT && location.name().equals("Mumbai"));
        assertThat(results).noneMatch(location -> location.name().equals("Dubai") || location.name().equals("400001"));
        server.verify();
    }

    @Test
    void searchesOfficialDistrictsWithoutGoogleOrClinicData() {
        var offline = new LocationSearchService(new LgdDistrictDirectory(), new GooglePlacesAutocomplete("", client));

        var result = offline.search("PUR NaG", TOKEN);

        assertThat(result.googleStatus()).isEqualTo(DISABLED);
        assertThat(result.suggestions()).anyMatch(location -> location.id().equals("157")
                && location.type() == DISTRICT && location.state().equals("Uttar Pradesh"));
        assertThat(offline.search("zzzzzzz", TOKEN).suggestions()).isEmpty();
        assertThat(offline.search("an", TOKEN).suggestions()).hasSize(12);
        server.verify();
    }

    @Test
    void retainsDistrictsWhenGoogleTimesOutOrReturnsQuotaError() {
        expectAutocomplete("luc", "(cities)").andRespond(withException(new SocketTimeoutException()));
        expectAutocomplete("luc", "(regions)").andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

        var result = locations.search("luc", TOKEN);

        assertThat(result.googleStatus()).isEqualTo(UNAVAILABLE);
        assertThat(result.suggestions()).extracting(LocationSuggestion::name).contains("Lucknow");
        assertThat(result.suggestions()).allMatch(location -> location.type() == DISTRICT);
        server.verify();
    }

    @Test
    void onlyResolvesIssuedSelectionOnceAndConfirmsTheParentCity() {
        expectAutocomplete("mum", "(cities)").andRespond(withSuccess(EMPTY, MediaType.APPLICATION_JSON));
        expectAutocomplete("mum", "(regions)").andRespond(withSuccess(MUMBAI_AREA, MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://places.googleapis.com/v1/places/central_id?languageCode=en&sessionToken=" + TOKEN))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-Goog-FieldMask", "id,types,addressComponents.longText,addressComponents.shortText,addressComponents.types"))
                .andRespond(withSuccess("""
                        {"id":"central_id","types":["sublocality_level_1","sublocality"],"addressComponents":[
                          {"longText":"Mumbai Central","types":["sublocality_level_1","sublocality"]},%s
                        ]}
                        """.formatted(IN_ADDRESS), MediaType.APPLICATION_JSON));

        locations.search("mum", TOKEN);

        assertThatThrownBy(() -> locations.resolvePrediction("unissued_id", TOKEN))
                .isInstanceOf(BadRequestException.class);
        assertThat(locations.resolvePrediction("central_id", TOKEN))
                .contains(new LocationSuggestion("central_id", "Mumbai Central", "Maharashtra", "Mumbai",
                        AREA, GOOGLE_PLACES, "central_id"));
        assertThatThrownBy(() -> locations.resolvePrediction("central_id", TOKEN))
                .isInstanceOf(BadRequestException.class);
        assertThat(locations.search("mum", TOKEN).googleStatus()).isEqualTo(UNAVAILABLE);
        server.verify();
    }

    @Test
    void reverseGeocodesRoundedCoordinatesAndAcceptsOnlyIndianLocalities() {
        server.expect(requestTo("https://geocode.googleapis.com/v4/geocode/location/19.076,72.878?languageCode=en&types=locality"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-Goog-Api-Key", "synthetic-key"))
                .andRespond(withSuccess("""
                        {"results":[{"placeId":"dubai_id","types":["locality"],"addressComponents":[
                          {"longText":"United Arab Emirates","shortText":"AE","types":["country"]}]},
                          {"placeId":"mumbai_id","types":["locality"],"addressComponents":[%s]}]}
                        """.formatted(IN_ADDRESS), MediaType.APPLICATION_JSON));

        assertThat(locations.nearby(new BigDecimal("19.07605"), new BigDecimal("72.87771")))
                .contains(new LocationSuggestion("mumbai_id", "Mumbai", "Maharashtra", null,
                        CITY, GOOGLE_GEOCODING, "mumbai_id"));
        server.verify();
    }

    @Test
    void validatesSearchAndTypedLocationBeforeProviderUse() {
        assertThatThrownBy(() -> locations.search("m", TOKEN)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> locations.search("m".repeat(101), TOKEN)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> locations.search("mum", "bad")).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> locations.resolveFilter("central_id", "AREA", "Mumbai Central", "Maharashtra", null))
                .isInstanceOf(BadRequestException.class);
        assertThat(locations.resolveFilter("central_id", "AREA", "Mumbai Central", "Maharashtra", "Mumbai").locality())
                .isEqualTo("mumbai central");
        assertThat(locations.resolveFilter("157", "DISTRICT", null, null, null).districtCode()).isEqualTo(157);
        server.verify();
    }

    @Test
    void failedSessionsDoNotExhaustCapacityForLaterSuggestions() {
        var provider = mock(GooglePlacesAutocomplete.class);
        when(provider.enabled()).thenReturn(true);
        var search = new LocationSearchService(new LgdDistrictDirectory(), provider);
        for (int i = 0; i < 512; i++) {
            search.search("xx", UUID.randomUUID().toString());
        }
        var format = new GooglePlacesAutocomplete.StructuredFormat(
                new GooglePlacesAutocomplete.Text("Mumbai"), new GooglePlacesAutocomplete.Text("Maharashtra, India"));
        when(provider.search(eq("mum"), eq("(cities)"), anyString())).thenReturn(Optional.of(List.of(
                new GooglePlacesAutocomplete.Prediction("city_id", List.of("locality"), format))));

        assertThat(search.search("mum", TOKEN).suggestions())
                .anyMatch(location -> location.type() == CITY && location.id().equals("city_id"));
    }

    private org.springframework.test.web.client.ResponseActions expectAutocomplete(String input, String type) {
        return server.expect(requestTo(AUTOCOMPLETE))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Goog-Api-Key", "synthetic-key"))
                .andExpect(jsonPath("$.input").value(input))
                .andExpect(jsonPath("$.includedPrimaryTypes", org.hamcrest.Matchers.is(List.of(type))))
                .andExpect(jsonPath("$.includedRegionCodes", org.hamcrest.Matchers.is(List.of("in"))))
                .andExpect(jsonPath("$.languageCode").value("en"))
                .andExpect(jsonPath("$.sessionToken").value(TOKEN));
    }
}
