package com.harsh.hospitalmanagement.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record LocationSuggestion(String id, String name, String state, String parentCity,
                                 Type type, Source source, String placeId) {
    public enum Type { CITY, AREA, DISTRICT }

    public enum Source { GOOGLE_PLACES, GOOGLE_GEOCODING, LGD }
}
