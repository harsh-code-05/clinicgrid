export const LOCATION_FILTER_DEFAULTS = {
  city: '',
  locationId: '',
  locationType: '',
  locationName: '',
  locationState: '',
  locationParentCity: '',
};

export function formatLocation({ name, state, parentCity }) {
  return [...new Set([name, parentCity, state].filter(Boolean))].join(', ');
}

export function locationDraft(filters) {
  const { city, locationId, locationType, locationName, locationState, locationParentCity } = filters;
  if (locationId && ['CITY', 'AREA', 'DISTRICT'].includes(locationType) && locationName && locationState) {
    const selection = {
      id: locationId,
      type: locationType,
      name: locationName,
      state: locationState,
      parentCity: locationParentCity,
      source: locationType === 'DISTRICT' ? 'LGD' : 'GOOGLE_PLACES',
    };
    return { text: formatLocation(selection), selection };
  }
  return { text: city ?? '', selection: city ? { name: city } : null };
}

export function selectedLocationFilters({ text, selection }) {
  if (!selection) {
    return text.trim() ? null : LOCATION_FILTER_DEFAULTS;
  }
  if (!selection.id) {
    return { ...LOCATION_FILTER_DEFAULTS, city: selection.name };
  }
  return {
    ...LOCATION_FILTER_DEFAULTS,
    locationId: selection.id,
    locationType: selection.type,
    locationName: selection.name,
    locationState: selection.state,
    locationParentCity: selection.parentCity ?? '',
  };
}

export function discoveryQuery(filters) {
  return new URLSearchParams(Object.entries(filters).filter(([, value]) => value));
}
