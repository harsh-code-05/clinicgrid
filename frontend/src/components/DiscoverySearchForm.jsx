import { useState } from 'react';
import LocationAutocomplete from './LocationAutocomplete';
import { locationDraft, selectedLocationFilters } from '../utils/discoveryLocation';

const MODES = [
  { value: '', label: 'Any consultation' },
  { value: 'IN_CLINIC', label: 'In-clinic' },
  { value: 'VIDEO', label: 'Video consultation' },
];

export default function DiscoverySearchForm({ initialSearch = {}, specializations, onSubmit, compact = false }) {
  const [search, setSearch] = useState({
    specializationId: initialSearch.specializationId ?? '',
    mode: initialSearch.mode ?? '',
  });
  const [location, setLocation] = useState(() => locationDraft(initialSearch));
  const [invalidLocation, setInvalidLocation] = useState(false);

  const update = (field) => (event) => {
    setSearch((current) => ({ ...current, [field]: event.target.value }));
  };
  const updateLocation = (draft) => {
    setLocation(draft);
    setInvalidLocation(false);
  };
  const submit = (event) => {
    event.preventDefault();
    const locationFilters = selectedLocationFilters(location);
    if (!locationFilters) {
      setInvalidLocation(true);
      return;
    }
    onSubmit({ ...search, ...locationFilters });
  };

  return (
    <form className={`discovery-search ${compact ? 'discovery-search--compact' : ''}`} onSubmit={submit}>
      <LocationAutocomplete value={location} onChange={updateLocation} invalid={invalidLocation} />

      <label>
        Specialty
        <select value={search.specializationId} onChange={update('specializationId')}>
          <option value="">Choose a specialty</option>
          {specializations.map((specialization) => (
            <option key={specialization.id} value={specialization.id}>
              {specialization.name}
            </option>
          ))}
        </select>
      </label>

      <label>
        Consultation mode
        <select value={search.mode} onChange={update('mode')}>
          {MODES.map((mode) => (
            <option key={mode.value} value={mode.value}>
              {mode.label}
            </option>
          ))}
        </select>
      </label>

      <button className="hms-button" type="submit">
        {compact ? 'Edit Search' : 'Search Doctors'}
      </button>
    </form>
  );
}
