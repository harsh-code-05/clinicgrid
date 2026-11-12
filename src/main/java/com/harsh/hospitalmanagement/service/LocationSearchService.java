package com.harsh.hospitalmanagement.service;

import com.harsh.hospitalmanagement.dto.LocationSuggestion;
import com.harsh.hospitalmanagement.dto.LocationSuggestion.Source;
import com.harsh.hospitalmanagement.dto.LocationSuggestion.Type;
import com.harsh.hospitalmanagement.exception.BadRequestException;
import com.harsh.hospitalmanagement.service.GooglePlacesAutocomplete.ComponentValue;
import com.harsh.hospitalmanagement.service.GooglePlacesAutocomplete.Prediction;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

@Service
public class LocationSearchService {
    static final int MAX_RESULTS = 12;
    private static final int MAX_LABEL_LENGTH = 120;
    private static final int MAX_SESSIONS = 512;
    private static final int MAX_SESSION_SEARCHES = 24;
    private static final long SESSION_TTL = Duration.ofMinutes(5).toNanos();

    private final LgdDistrictDirectory districts;
    private final GooglePlacesAutocomplete google;
    private final Map<UUID, SearchSession> sessions = new HashMap<>();

    LocationSearchService(LgdDistrictDirectory districts, GooglePlacesAutocomplete google) {
        this.districts = districts;
        this.google = google;
    }

    public Results search(String query, String token) {
        if (query == null || query.isBlank()) {
            throw new BadRequestException("Query parameter 'q' must not be blank.");
        }
        String input = query.strip();
        if (input.length() < 2) {
            throw new BadRequestException("Query parameter 'q' must be at least 2 characters.");
        }
        if (input.length() > 100 || input.chars().anyMatch(Character::isISOControl)) {
            throw new BadRequestException("Query parameter 'q' must be at most 100 characters and contain no control characters.");
        }

        UUID sessionId = token == null ? null : sessionId(token);
        var result = !google.enabled() || sessionId == null
                ? new Results(List.of(), GoogleStatus.DISABLED) : searchGoogle(input, sessionId);
        var combined = new ArrayList<>(result.suggestions());
        combined.addAll(districts.search(input, MAX_RESULTS - combined.size()));
        return new Results(List.copyOf(combined), result.googleStatus());
    }

    private Results searchGoogle(String input, UUID id) {
        SearchSession session = session(id, true);
        if (session == null || !tryLock(session)) {
            return new Results(List.of(), GoogleStatus.UNAVAILABLE);
        }
        try {
            if (session.retired || session.consumed || session.searches >= MAX_SESSION_SEARCHES
                    || session.expired(System.nanoTime())) {
                return new Results(List.of(), GoogleStatus.UNAVAILABLE);
            }
            session.searches++;
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                var cities = executor.submit(() -> google.search(input, "(cities)", id.toString()));
                var regions = executor.submit(() -> google.search(input, "(regions)", id.toString()));
                try {
                    var cityPredictions = cities.get();
                    var regionPredictions = regions.get();
                    var results = new ArrayList<LocationSuggestion>();
                    var ids = new HashSet<String>();
                    var names = new HashSet<List<String>>();
                    addPredictions(results, ids, names, cityPredictions.orElse(List.of()), true);
                    addPredictions(results, ids, names, regionPredictions.orElse(List.of()), false);
                    results.forEach(suggestion -> session.places.put(suggestion.placeId(), suggestion.type()));
                    return new Results(results, cityPredictions.isPresent() && regionPredictions.isPresent()
                            ? GoogleStatus.AVAILABLE : GoogleStatus.UNAVAILABLE);
                } finally {
                    cities.cancel(true);
                    regions.cancel(true);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new Results(List.of(), GoogleStatus.UNAVAILABLE);
            } catch (java.util.concurrent.ExecutionException e) {
                return new Results(List.of(), GoogleStatus.UNAVAILABLE);
            }
        } finally {
            if (!session.consumed && session.places.isEmpty() && !session.lock.hasQueuedThreads()) {
                discardEmptySession(id, session);
            }
            session.lock.unlock();
        }
    }

    private void addPredictions(List<LocationSuggestion> results, Set<String> ids, Set<List<String>> names,
                                List<Prediction> predictions, boolean cityCollection) {
        for (Prediction prediction : predictions) {
            var format = prediction.structuredFormat();
            if (!validPlaceId(prediction.placeId()) || format == null || format.mainText() == null
                    || !validLabel(format.mainText().text())) {
                continue;
            }
            String name = format.mainText().text().strip();
            Type type = isArea(prediction.types()) ? Type.AREA
                    : cityCollection || hasType(prediction.types(), "locality") ? Type.CITY : null;
            if (type == null) {
                continue;
            }
            String region = format.secondaryText() == null ? null : format.secondaryText().text();
            var address = predictionAddress(name, region);
            if (address.isEmpty()) {
                continue;
            }
            String parent = type == Type.AREA ? address.get().parentCity() : null;
            var nameKey = List.of(type.name(), normalize(name), normalize(address.get().state()),
                    parent == null ? "" : normalize(parent));
            if (!ids.contains(prediction.placeId()) && names.add(nameKey)) {
                ids.add(prediction.placeId());
                results.add(new LocationSuggestion(prediction.placeId(), name, address.get().state(), parent,
                        type, Source.GOOGLE_PLACES, prediction.placeId()));
            }
        }
    }

    public Optional<LocationSuggestion> resolvePrediction(String placeId, String token) {
        UUID id = sessionId(token);
        if (!validPlaceId(placeId)) {
            throw new BadRequestException("Select a location from the current suggestions.");
        }
        SearchSession session = session(id, false);
        if (session == null) {
            throw new BadRequestException("Location suggestions expired. Search again.");
        }
        if (!tryLock(session)) {
            return Optional.empty();
        }
        try {
            Type type = session.places.get(placeId);
            if (session.consumed || type == null || session.expired(System.nanoTime())) {
                throw new BadRequestException("Location suggestions expired. Search again.");
            }
            // A Place Details request terminates the token, including when its result is lost or unusable.
            session.consumed = true;
            session.places.clear();
            return google.details(placeId, id.toString())
                    .filter(details -> placeId.equals(details.id()))
                    .flatMap(details -> resolveAddress(placeId, type, Source.GOOGLE_PLACES,
                            details.types(), details.addressComponents()));
        } finally {
            session.lock.unlock();
        }
    }

    public Optional<LocationSuggestion> nearby(BigDecimal latitude, BigDecimal longitude) {
        String lat = latitude.setScale(3, RoundingMode.HALF_UP).toPlainString();
        String lng = longitude.setScale(3, RoundingMode.HALF_UP).toPlainString();
        return google.reverse(lat, lng)
                .filter(response -> response.results() != null)
                .stream().flatMap(response -> response.results().stream())
                .filter(result -> result != null && validPlaceId(result.placeId())
                        && hasType(result.types(), "locality"))
                .flatMap(result -> resolveAddress(result.placeId(), Type.CITY, Source.GOOGLE_GEOCODING,
                        result.types(), result.addressComponents()).stream())
                .findFirst();
    }

    private Optional<LocationSuggestion> resolveAddress(String id, Type type, Source source, List<String> types,
                                                         List<ComponentValue> components) {
        if (components == null || components.stream().noneMatch(component -> component != null
                && hasType(component.types(), "country") && "IN".equalsIgnoreCase(component.shortText()))) {
            return Optional.empty();
        }
        String parentCity = componentName(components, "locality")
                .or(() -> componentName(components, "postal_town"))
                .orElse(null);
        String state = componentName(components, "administrative_area_level_1")
                .flatMap(districts::canonicalState).orElse(null);
        if (state == null) {
            return Optional.empty();
        }
        if (type == Type.AREA) {
            var area = components.stream().filter(component -> component != null && isArea(component.types())
                            && component.types().stream().anyMatch(value -> value != null && isArea(List.of(value))
                                    && hasType(types, value)))
                    .map(ComponentValue::longText).filter(LocationSearchService::validLabel).findFirst();
            return validLabel(parentCity) ? area.map(name -> new LocationSuggestion(id, name, state, parentCity, type, source, id))
                    : Optional.empty();
        }
        String city = parentCity;
        if (city == null && hasType(types, "administrative_area_level_3")) {
            city = componentName(components, "administrative_area_level_3").orElse(null);
        }
        if (city == null && "Delhi".equals(state) && hasType(types, "administrative_area_level_1")) {
            city = state;
        }
        return validLabel(city)
                ? Optional.of(new LocationSuggestion(id, city, state, null, type, source, id)) : Optional.empty();
    }

    public Filter resolveFilter(String id, String type, String name, String state, String parentCity) {
        if ("DISTRICT".equals(type)) {
            var district = districts.find(id)
                    .orElseThrow(() -> new BadRequestException("Unknown LGD district code."));
            return new Filter(null, Set.of(), Integer.valueOf(district.id()), null);
        }
        if (validPlaceId(id) && validLabel(name) && validLabel(state)) {
            Set<String> states = districts.stateValues(state);
            if (!states.isEmpty()) {
                if ("CITY".equals(type)) {
                    return new Filter(normalize(name), states, null, null);
                }
                if ("AREA".equals(type) && validLabel(parentCity)) {
                    return new Filter(normalize(parentCity), states, null, normalize(name));
                }
            }
        }
        throw new BadRequestException("Select a valid city, area or district for the location filter.");
    }

    private Optional<PredictionAddress> predictionAddress(String name, String region) {
        if (region != null && !region.isBlank()) {
            String[] parts = region.split(",");
            int last = parts.length - 1;
            if (last < 0) {
                return Optional.empty();
            }
            if ("india".equals(normalize(parts[last]))) {
                last--;
            }
            if (last >= 0) {
                String parent = last > 0 && validLabel(parts[last - 1]) ? parts[last - 1].strip() : null;
                return districts.canonicalState(parts[last]).map(state -> new PredictionAddress(state, parent));
            }
        }
        // Some city-states such as Delhi have no separate state label in the prediction.
        return districts.canonicalState(name).map(state -> new PredictionAddress(state, null));
    }

    private synchronized SearchSession session(UUID id, boolean create) {
        long now = System.nanoTime();
        sessions.values().removeIf(session -> session.expired(now));
        SearchSession existing = sessions.get(id);
        if (existing != null || !create || sessions.size() >= MAX_SESSIONS) {
            return existing;
        }
        var session = new SearchSession(now);
        sessions.put(id, session);
        return session;
    }

    private synchronized void discardEmptySession(UUID id, SearchSession session) {
        session.retired = true;
        sessions.remove(id, session);
    }

    private static boolean tryLock(SearchSession session) {
        try {
            return session.lock.tryLock(4, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static UUID sessionId(String token) {
        if (token != null && token.length() == 36) {
            try {
                UUID id = UUID.fromString(token);
                if (id.version() == 4 && id.variant() == 2 && id.toString().equalsIgnoreCase(token)) {
                    return id;
                }
            } catch (IllegalArgumentException ignored) {
                // Return the same validation error for all invalid tokens.
            }
        }
        throw new BadRequestException("Use a valid location search session token.");
    }

    private static Optional<String> componentName(List<ComponentValue> components, String type) {
        return components.stream().filter(component -> component != null && hasType(component.types(), type))
                .map(ComponentValue::longText).filter(LocationSearchService::validLabel).findFirst();
    }

    private static boolean hasType(List<String> types, String type) {
        return types != null && types.contains(type);
    }

    private static boolean isArea(List<String> types) {
        return types != null && types.stream().anyMatch(type -> type != null
                && (type.equals("neighborhood") || type.equals("sublocality") || type.startsWith("sublocality_level_")));
    }

    private static boolean validLabel(String value) {
        return value != null && !value.isBlank() && value.length() <= MAX_LABEL_LENGTH
                && value.chars().noneMatch(Character::isISOControl);
    }

    private static boolean validPlaceId(String id) {
        return id != null && id.matches("[a-zA-Z0-9_-]{1,255}");
    }

    private static String normalize(String value) {
        return value.strip().toLowerCase(Locale.ROOT);
    }

    public enum GoogleStatus { AVAILABLE, UNAVAILABLE, DISABLED }

    public record Results(List<LocationSuggestion> suggestions, GoogleStatus googleStatus) {}

    public record Filter(String city, Set<String> states, Integer districtCode, String locality) {}

    private record PredictionAddress(String state, String parentCity) {}

    private static final class SearchSession {
        private final long created;
        private final ReentrantLock lock = new ReentrantLock();
        // Only IDs and selection type are retained, for one short-lived session.
        private final Map<String, Type> places = new HashMap<>();
        private int searches;
        private boolean consumed;
        private boolean retired;

        private SearchSession(long created) {
            this.created = created;
        }

        private boolean expired(long now) {
            return now - created >= SESSION_TTL;
        }
    }
}
