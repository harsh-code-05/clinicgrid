import { useCallback, useEffect, useId, useRef, useState } from 'react';
import api from '../api/axios';
import googleMapsAttribution from '../assets/discovery/google-maps-attribution.png';
import { formatLocation } from '../utils/discoveryLocation';
import { RequestSequencer } from '../utils/requestSequencer';

const DEBOUNCE_DELAY_MS = 350;
const MIN_LOCATION_CHARS = 2;
const MAX_LOCATION_CHARS = 100;
const SEARCHING = 'Searching locations…';

function isSuggestion(location) {
  const isGooglePrediction = location?.source === 'GOOGLE_PLACES' && ['CITY', 'AREA'].includes(location.type);
  const isNearbyCity = location?.source === 'GOOGLE_GEOCODING' && location.type === 'CITY';
  return typeof location?.id === 'string' && location.id.trim() &&
    typeof location.name === 'string' && location.name.trim() &&
    typeof location.state === 'string' && location.state.trim() &&
    (location.parentCity == null || typeof location.parentCity === 'string') &&
    ((location.type === 'DISTRICT' && location.source === 'LGD') ||
      ((isGooglePrediction || isNearbyCity) && location.placeId === location.id));
}

function canceled(failure) {
  return failure?.name === 'CanceledError' || failure?.code === 'ERR_CANCELED';
}

function LocationPin() {
  return (
    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" aria-hidden="true">
      <path d="M19 10c0 5-7 11-7 11S5 15 5 10a7 7 0 1 1 14 0Z" />
      <circle cx="12" cy="10" r="2.4" />
    </svg>
  );
}

export default function LocationAutocomplete({ value, onChange, invalid }) {
  const id = useId();
  const listId = `${id}-suggestions`;
  const [suggestions, setSuggestions] = useState([]);
  const [isOpen, setIsOpen] = useState(false);
  const [progress, setProgress] = useState('');
  const [error, setError] = useState('');
  const [googleStatus, setGoogleStatus] = useState('');
  const [hasNearbyResult, setHasNearbyResult] = useState(false);
  const [activeIndex, setActiveIndex] = useState(-1);
  const [sequencer] = useState(() => new RequestSequencer());

  const wrapperRef = useRef(null);
  const debounceTimerRef = useRef(null);
  const sessionTokenRef = useRef(null);

  const stopRequests = useCallback(() => {
    clearTimeout(debounceTimerRef.current);
    sequencer.cancelActive();
  }, [sequencer]);

  const close = useCallback(() => {
    stopRequests();
    sessionTokenRef.current = null;
    setIsOpen(false);
    setProgress('');
    setError('');
    setGoogleStatus('');
    setHasNearbyResult(false);
    setActiveIndex(-1);
    setSuggestions([]);
  }, [stopRequests]);

  useEffect(() => {
    if (!isOpen) return;
    const handleClickOutside = (event) => {
      if (wrapperRef.current && !wrapperRef.current.contains(event.target)) close();
    };
    document.addEventListener('pointerdown', handleClickOutside);
    return () => document.removeEventListener('pointerdown', handleClickOutside);
  }, [close, isOpen]);

  useEffect(() => {
    sequencer.mount();
    return () => {
      clearTimeout(debounceTimerRef.current);
      sessionTokenRef.current = null;
      sequencer.dispose();
    };
  }, [sequencer]);

  useEffect(() => {
    if (activeIndex >= 0) {
      document.getElementById(`${listId}-${activeIndex}`)?.scrollIntoView({ block: 'nearest' });
    }
  }, [activeIndex, listId]);

  const fetchSuggestions = async (query) => {
    const request = sequencer.startRequest();
    try {
      const sessionToken = sessionTokenRef.current ??= globalThis.crypto?.randomUUID?.();
      const { data } = await api.get('/v2/discovery/cities', {
        params: { q: query, sessionToken },
        signal: request.signal,
      });
      if (!request.isLatest()) return;
      if (!Array.isArray(data?.suggestions) ||
          !data.suggestions.every((location) => isSuggestion(location) && location.source !== 'GOOGLE_GEOCODING') ||
          !['AVAILABLE', 'UNAVAILABLE', 'DISABLED'].includes(data.googleStatus)) {
        throw new Error('Unexpected location suggestions response.');
      }
      const googleLocations = data.suggestions.filter((location) => location.source === 'GOOGLE_PLACES');
      const districts = data.suggestions.filter((location) => location.source === 'LGD');
      setSuggestions([...googleLocations, ...districts]);
      setGoogleStatus(data.googleStatus);
    } catch (failure) {
      if (request.isLatest() && !canceled(failure)) {
        setError('Unable to load location suggestions. Try again.');
      }
    } finally {
      if (request.isLatest()) setProgress('');
    }
  };

  const scheduleSearch = (text) => {
    const query = text.trim();
    stopRequests();
    if (!query) sessionTokenRef.current = null;
    setSuggestions([]);
    setError('');
    setGoogleStatus('');
    setHasNearbyResult(false);
    setActiveIndex(-1);
    setIsOpen(true);
    if (query.length < MIN_LOCATION_CHARS) {
      setProgress('');
      return;
    }
    setProgress(SEARCHING);
    debounceTimerRef.current = setTimeout(() => fetchSuggestions(query), DEBOUNCE_DELAY_MS);
  };

  const open = () => {
    if (isOpen) return;
    if (value.selection) setIsOpen(true);
    else scheduleSearch(value.text);
  };

  const commitLocation = (location) => {
    close();
    onChange({ text: formatLocation(location), selection: location });
  };

  const selectLocation = async (location) => {
    if (location.source === 'LGD' || hasNearbyResult) {
      commitLocation(location);
      return;
    }

    const sessionToken = sessionTokenRef.current;
    sessionTokenRef.current = null;
    stopRequests();
    setProgress('Confirming location…');
    setError('');
    setGoogleStatus('');
    setActiveIndex(-1);
    setSuggestions([]);

    const request = sequencer.startRequest();
    try {
      if (!sessionToken) throw new Error('Location session expired.');
      const { data } = await api.post('/v2/discovery/locations/resolve', {
        placeId: location.placeId,
        sessionToken,
      }, { signal: request.signal });
      if (!request.isLatest()) return;
      if (!isSuggestion(data) || data.source !== 'GOOGLE_PLACES' || data.placeId !== location.placeId) {
        throw new Error('Unexpected confirmed location response.');
      }
      commitLocation(data);
    } catch (failure) {
      if (request.isLatest() && !canceled(failure)) {
        setError('Unable to confirm this location. Edit your search and try again.');
      }
    } finally {
      if (request.isLatest()) setProgress('');
    }
  };

  const requestNearbyLocation = () => {
    stopRequests();
    sessionTokenRef.current = null;
    setSuggestions([]);
    setError('');
    setGoogleStatus('');
    setHasNearbyResult(false);
    setActiveIndex(-1);
    const geolocation = navigator.geolocation;
    if (!geolocation) {
      setProgress('');
      setError('Location is unavailable in this browser. Search by name instead.');
      return;
    }

    setProgress('Finding your location…');
    const request = sequencer.startRequest();
    geolocation.getCurrentPosition(async ({ coords: { latitude, longitude } }) => {
      if (!request.isLatest()) return;
      try {
        const { data } = await api.post('/v2/discovery/locations/nearby', {
          latitude: Math.round(latitude * 1000) / 1000,
          longitude: Math.round(longitude * 1000) / 1000,
        }, { signal: request.signal });
        if (!request.isLatest()) return;
        if (!isSuggestion(data) || data.source !== 'GOOGLE_GEOCODING') {
          throw new Error('Unexpected nearby location response.');
        }
        setHasNearbyResult(true);
        setSuggestions([data]);
      } catch (failure) {
        if (request.isLatest() && !canceled(failure)) {
          setError('Unable to find a nearby city. Search by name instead.');
        }
      } finally {
        if (request.isLatest()) setProgress('');
      }
    }, (failure) => {
      if (!request.isLatest()) return;
      setProgress('');
      const messages = {
        1: 'Location permission was denied. Search by name instead.',
        2: 'Your location is unavailable. Search by name instead.',
        3: 'The location request timed out. Search by name instead.',
      };
      setError(messages[failure.code] ?? messages[2]);
    }, { timeout: 10000, maximumAge: 0 });
  };

  const handleKeyDown = (event) => {
    if (event.nativeEvent?.isComposing) return;
    if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
      event.preventDefault();
      open();
      const optionCount = suggestions.length + 1;
      setActiveIndex((previous) => event.key === 'ArrowDown'
        ? (previous + 1) % optionCount
        : previous > 0 ? previous - 1 : optionCount - 1);
    } else if (isOpen && event.key === 'Enter' && activeIndex >= 0) {
      event.preventDefault();
      if (activeIndex === 0) {
        if (!progress || progress === SEARCHING) requestNearbyLocation();
      } else if (suggestions[activeIndex - 1]) {
        selectLocation(suggestions[activeIndex - 1]);
      }
    } else if (isOpen && event.key === 'Escape') {
      event.preventDefault();
      close();
    }
  };

  const renderOption = (location, index) => (
    <div
      key={`${location.source}-${location.id}`}
      id={`${listId}-${index + 1}`}
      role="option"
      aria-selected={activeIndex === index + 1}
      className={`city-autocomplete__item ${activeIndex === index + 1 ? 'is-active' : ''}`}
      onMouseDown={(event) => event.preventDefault()}
      onClick={() => selectLocation(location)}
    >
      <span className="city-autocomplete__result">
        <span>{location.name}</span>
        {(location.parentCity || location.state) && (
          <span className="city-autocomplete__details">
            {formatLocation({ name: location.parentCity || location.state, state: location.state })}
          </span>
        )}
      </span>
      <span className="city-autocomplete__type">{location.type}</span>
    </div>
  );

  const googleCount = suggestions.filter((location) => location.source !== 'LGD').length;
  const nearbyDisabled = Boolean(progress && progress !== SEARCHING);
  const searchStarted = !value.selection && value.text.trim().length >= MIN_LOCATION_CHARS;

  return (
    <div
      className="discovery-search__field city-autocomplete-wrapper"
      ref={wrapperRef}
      onBlur={(event) => {
        if (event.relatedTarget && !event.currentTarget.contains(event.relatedTarget)) close();
      }}
    >
      <div className="city-autocomplete__label">
        <label htmlFor={id}>Location</label>
        {value.selection?.source?.startsWith('GOOGLE_') && (
          <span className="city-autocomplete__selected-attribution" translate="no">Google Maps</span>
        )}
      </div>
      <div className="city-autocomplete__input-container">
        <span className="city-autocomplete__pin"><LocationPin /></span>
        <input
          id={id}
          type="text"
          role="combobox"
          autoComplete="off"
          spellCheck={false}
          maxLength={MAX_LOCATION_CHARS}
          aria-autocomplete="list"
          aria-expanded={isOpen}
          aria-controls={isOpen ? listId : undefined}
          aria-activedescendant={isOpen && activeIndex >= 0 ? `${listId}-${activeIndex}` : undefined}
          aria-invalid={invalid || undefined}
          aria-describedby={invalid ? `${id}-validation` : undefined}
          value={value.text}
          onChange={(event) => {
            const text = event.target.value;
            onChange({ text, selection: null });
            scheduleSearch(text);
          }}
          onKeyDown={handleKeyDown}
          onFocus={open}
          onClick={open}
          placeholder="Search for a city, area or district"
        />
        {isOpen && (
          <div className="city-autocomplete__dropdown">
            <div id={listId} className="city-autocomplete__list" role="listbox" aria-label="Location suggestions">
              <div
                id={`${listId}-0`}
                role="option"
                aria-selected={activeIndex === 0}
                aria-disabled={nearbyDisabled}
                className={`city-autocomplete__nearby ${activeIndex === 0 ? 'is-active' : ''}`}
                onMouseDown={(event) => event.preventDefault()}
                onClick={() => { if (!nearbyDisabled) requestNearbyLocation(); }}
              >
                <LocationPin />
                <span>Use my location</span>
              </div>
              {googleCount > 0 && (
                <div className="city-autocomplete__google" role="group" aria-label="Suggestions from Google Maps">
                  <div className="city-autocomplete__attribution">
                    <img src={googleMapsAttribution} alt="Google Maps" />
                  </div>
                  {hasNearbyResult && <p className="city-autocomplete__status">Select the nearby city to use it.</p>}
                  {suggestions.slice(0, googleCount).map(renderOption)}
                </div>
              )}
              {googleCount < suggestions.length && (
                <div role="group" aria-label="District suggestions">
                  {suggestions.slice(googleCount).map((location, index) => renderOption(location, index + googleCount))}
                </div>
              )}
            </div>
            {progress && <div className="city-autocomplete__status" role="status">{progress}</div>}
            {error && <div className="city-autocomplete__status city-autocomplete__status--error" role="alert">{error}</div>}
            {(googleStatus === 'UNAVAILABLE' || googleStatus === 'DISABLED') && (
              <div className="city-autocomplete__status" role="status">
                City and area suggestions may be unavailable. You can still search for districts.
              </div>
            )}
            {!progress && !error && !suggestions.length && searchStarted && (
              <div className="city-autocomplete__status city-autocomplete__status--empty" role="status">
                No matching locations found
              </div>
            )}
          </div>
        )}
      </div>
      {invalid && (
        <span id={`${id}-validation`} className="city-autocomplete__validation" role="alert">
          Select a location from the suggestions or clear the field.
        </span>
      )}
    </div>
  );
}
