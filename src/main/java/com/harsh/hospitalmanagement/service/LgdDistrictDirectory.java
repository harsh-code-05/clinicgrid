package com.harsh.hospitalmanagement.service;

import com.harsh.hospitalmanagement.dto.LocationSuggestion;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Component
public class LgdDistrictDirectory {
    private static final String HEADER = "district_code\tdistrict_name\tstate_code\tstate_name";

    // Common clinic input abbreviations and alternative spellings, indexed by LGD state code.
    private static final Map<Integer, List<String>> STATE_ALIASES = Map.ofEntries(
            Map.entry(1, List.of("JK", "Jammu and Kashmir", "Jammu & Kashmir")),
            Map.entry(2, List.of("HP")), Map.entry(3, List.of("PB")),
            Map.entry(4, List.of("CH")), Map.entry(5, List.of("UK", "Uttarakhand", "Uttaranchal")),
            Map.entry(6, List.of("HR")), Map.entry(7, List.of("DL", "Delhi", "NCT of Delhi", "New Delhi")),
            Map.entry(8, List.of("RJ")), Map.entry(9, List.of("UP")),
            Map.entry(10, List.of("BR")), Map.entry(11, List.of("SK")), Map.entry(12, List.of("AR")),
            Map.entry(13, List.of("NL")), Map.entry(14, List.of("MN")), Map.entry(15, List.of("MZ")),
            Map.entry(16, List.of("TR")), Map.entry(17, List.of("ML")), Map.entry(18, List.of("AS")),
            Map.entry(19, List.of("WB")), Map.entry(20, List.of("JH")),
            Map.entry(21, List.of("OD", "OR", "Odisha", "Orissa")), Map.entry(22, List.of("CG")),
            Map.entry(23, List.of("MP")), Map.entry(24, List.of("GJ")),
            Map.entry(38, List.of("DH", "DN", "DD", "Dadra and Nagar Haveli and Daman and Diu",
                    "Dadra & Nagar Haveli and Daman & Diu")),
            Map.entry(27, List.of("MH")), Map.entry(28, List.of("AP")), Map.entry(29, List.of("KA")),
            Map.entry(30, List.of("GA")), Map.entry(31, List.of("LD")), Map.entry(32, List.of("KL")),
            Map.entry(33, List.of("TN")), Map.entry(34, List.of("PY", "Puducherry", "Pondicherry")),
            Map.entry(35, List.of("AN", "Andaman and Nicobar Islands", "Andaman & Nicobar Islands")),
            Map.entry(36, List.of("TG", "TS")), Map.entry(37, List.of("LA")));

    private final Map<String, LocationSuggestion> districts;
    private final Map<String, String> canonicalStates;
    private final Map<String, Set<String>> stateValues;

    public LgdDistrictDirectory() {
        Map<String, LocationSuggestion> rows = new LinkedHashMap<>();
        Map<Integer, String> states = new HashMap<>();
        try (var reader = new BufferedReader(new InputStreamReader(
                new ClassPathResource("locations/lgd-districts.tsv").getInputStream(), StandardCharsets.UTF_8))) {
            if (!HEADER.equals(reader.readLine())) {
                throw new IllegalStateException("Unexpected LGD district directory header");
            }
            String line;
            while ((line = reader.readLine()) != null) {
                String[] fields = line.split("\t", -1);
                if (fields.length != 4 || !fields[0].matches("[1-9][0-9]{0,8}") || fields[1].isBlank()
                        || !fields[2].matches("[1-9][0-9]?") || fields[3].isBlank()) {
                    throw new IllegalStateException("Invalid LGD district directory row");
                }
                int stateCode = Integer.parseInt(fields[2]);
                String oldState = states.putIfAbsent(stateCode, fields[3]);
                if (oldState != null && !oldState.equals(fields[3])) {
                    throw new IllegalStateException("Inconsistent LGD state name");
                }
                var district = new LocationSuggestion(fields[0], fields[1], fields[3], null,
                        LocationSuggestion.Type.DISTRICT, LocationSuggestion.Source.LGD, null);
                if (rows.putIfAbsent(district.id(), district) != null) {
                    throw new IllegalStateException("Duplicate LGD district code");
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot load the bundled LGD district directory", e);
        }
        if (!states.keySet().equals(STATE_ALIASES.keySet())) {
            throw new IllegalStateException("LGD district directory must cover all Indian states and Union Territories");
        }
        districts = Map.copyOf(rows);
        Map<String, String> names = new HashMap<>();
        Map<String, Set<String>> variants = new HashMap<>();
        states.forEach((code, name) -> {
            Set<String> values = Stream.concat(Stream.of(name), STATE_ALIASES.get(code).stream())
                    .map(LgdDistrictDirectory::normalize).collect(Collectors.toUnmodifiableSet());
            values.forEach(value -> names.put(value, name));
            variants.put(name, values);
        });
        canonicalStates = Map.copyOf(names);
        stateValues = Map.copyOf(variants);
    }

    public Optional<LocationSuggestion> find(String districtCode) {
        return districtCode == null ? Optional.empty() : Optional.ofNullable(districts.get(districtCode));
    }

    List<LocationSuggestion> search(String input, int limit) {
        String query = normalize(input);
        return districts.values().stream()
                .filter(district -> normalize(district.name()).contains(query))
                .sorted(Comparator.comparingInt((LocationSuggestion district) -> rank(district.name(), query))
                        .thenComparing(LocationSuggestion::name, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(LocationSuggestion::state)
                        .thenComparing(LocationSuggestion::id))
                .limit(limit).toList();
    }

    Optional<String> canonicalState(String value) {
        return Optional.ofNullable(canonicalStates.get(normalize(value)));
    }

    public Set<String> stateValues(String state) {
        return canonicalState(state).map(stateValues::get).orElse(Set.of());
    }

    static String normalize(String value) {
        return value.strip().toLowerCase(Locale.ROOT);
    }

    private static int rank(String name, String query) {
        String normalized = normalize(name);
        if (normalized.equals(query)) {
            return 0;
        }
        return normalized.startsWith(query) ? 1 : 2;
    }
}
