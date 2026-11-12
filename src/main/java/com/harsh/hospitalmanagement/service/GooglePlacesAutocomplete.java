package com.harsh.hospitalmanagement.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

@Component
class GooglePlacesAutocomplete {
    private static final String PLACES = "https://places.googleapis.com/v1/places";
    private static final String GEOCODE = "https://geocode.googleapis.com/v4/geocode/location";
    private static final String PREDICTION_FIELDS = "suggestions.placePrediction.placeId,suggestions.placePrediction.types,"
            + "suggestions.placePrediction.structuredFormat.mainText.text,"
            + "suggestions.placePrediction.structuredFormat.secondaryText.text";
    private static final String COMPONENT_FIELDS = "addressComponents.longText,addressComponents.shortText,addressComponents.types";
    private static final String GEOCODE_FIELDS = "results.placeId,results.types,"
            + "results.addressComponents.longText,results.addressComponents.shortText,results.addressComponents.types";
    private static final long ONE_MINUTE = Duration.ofMinutes(1).toNanos();
    private static final int REQUESTS_PER_MINUTE = 60;
    private static final int MAX_PREDICTIONS = 5;

    private final String apiKey;
    private final RestClient client;
    private final Semaphore inFlight = new Semaphore(4);
    private final Deque<Long> requests = new ArrayDeque<>(REQUESTS_PER_MINUTE);

    @Autowired
    GooglePlacesAutocomplete(@Value("${GOOGLE_MAPS_API_KEY:}") String apiKey) {
        this(apiKey, apiKey.isBlank() ? null : newClient());
    }

    GooglePlacesAutocomplete(String apiKey, RestClient client) {
        this.apiKey = apiKey.strip();
        this.client = client;
    }

    boolean enabled() {
        return !apiKey.isEmpty();
    }

    Optional<List<Prediction>> search(String input, String type, String token) {
        return call(() -> client.post().uri(PLACES + ":autocomplete")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-Goog-Api-Key", apiKey)
                    .header("X-Goog-FieldMask", PREDICTION_FIELDS)
                    .body(new Request(input, List.of("in"), List.of(type), "en", token))
                    .retrieve().body(Response.class))
                .map(response -> response.suggestions() == null ? List.of()
                        : response.suggestions().stream().limit(MAX_PREDICTIONS)
                                .filter(item -> item != null && item.placePrediction() != null)
                                .map(Suggestion::placePrediction).toList());
    }

    Optional<Details> details(String placeId, String token) {
        return call(() -> client.get().uri(PLACES + "/{id}?languageCode=en&sessionToken={token}", placeId, token)
                .header("X-Goog-Api-Key", apiKey)
                .header("X-Goog-FieldMask", "id,types," + COMPONENT_FIELDS)
                .retrieve().body(Details.class));
    }

    Optional<Geocode> reverse(String latitude, String longitude) {
        return call(() -> client.get().uri(GEOCODE + "/{latitude},{longitude}?languageCode=en&types=locality",
                        latitude, longitude)
                .header("X-Goog-Api-Key", apiKey)
                .header("X-Goog-FieldMask", GEOCODE_FIELDS)
                .retrieve().body(Geocode.class));
    }

    private <T> Optional<T> call(Supplier<T> request) {
        if (!enabled() || !inFlight.tryAcquire()) {
            return Optional.empty();
        }
        try {
            return reserveRequest() ? Optional.ofNullable(request.get()) : Optional.empty();
        } catch (RestClientException e) {
            // Never log provider exceptions. They may contain location query parameters.
            return Optional.empty();
        } finally {
            inFlight.release();
        }
    }

    private synchronized boolean reserveRequest() {
        long now = System.nanoTime();
        while (!requests.isEmpty() && now - requests.getFirst() >= ONE_MINUTE) {
            requests.removeFirst();
        }
        if (requests.size() >= REQUESTS_PER_MINUTE) {
            return false;
        }
        requests.addLast(now);
        return true;
    }

    private static RestClient newClient() {
        var transport = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(1_500))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        var requestFactory = new JdkClientHttpRequestFactory(transport);
        requestFactory.setReadTimeout(Duration.ofMillis(3_000));
        return RestClient.builder().requestFactory(requestFactory).build();
    }

    private record Request(String input, List<String> includedRegionCodes,
                           List<String> includedPrimaryTypes, String languageCode, String sessionToken) {}

    private record Response(List<Suggestion> suggestions) {}

    private record Suggestion(Prediction placePrediction) {}

    record Prediction(String placeId, List<String> types, StructuredFormat structuredFormat) {}

    record StructuredFormat(Text mainText, Text secondaryText) {}

    record Text(String text) {}

    record Details(String id, List<String> types, List<ComponentValue> addressComponents) {}

    record Geocode(List<GeocodeResult> results) {}

    record GeocodeResult(String placeId, List<String> types, List<ComponentValue> addressComponents) {}

    record ComponentValue(String longText, String shortText, List<String> types) {}
}
