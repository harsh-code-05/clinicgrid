export default function SpecialtyIcon({ name }) {
  const normalized = (name || '').trim().toLowerCase();

  const props = {
    width: '24',
    height: '24',
    viewBox: '0 0 24 24',
    fill: 'none',
    stroke: 'currentColor',
    strokeWidth: '2',
    strokeLinecap: 'round',
    strokeLinejoin: 'round',
    'aria-hidden': 'true',
  };

  // 1. Cardiology: Heart with pulse
  if (normalized.includes('cardio')) {
    return (
      <svg {...props}>
        <path d="M19 14c1.49-1.46 3-3.21 3-5.5A5.5 5.5 0 0 0 16.5 3c-1.76 0-3 .5-4.5 2-1.5-1.5-2.74-2-4.5-2A5.5 5.5 0 0 0 2 8.5c0 2.3 1.5 4.05 3 5.5l7 7Z" />
        <path d="M3.22 12H9.5l1.5-3 2 6 1.5-3h4.28" />
      </svg>
    );
  }

  // 2. Neurology: Brain / Neural pathways
  if (normalized.includes('neuro')) {
    return (
      <svg {...props}>
        <path d="M9.5 2A2.5 2.5 0 0 1 12 4.5v15a2.5 2.5 0 0 1-4.96.44 2.5 2.5 0 0 1-2.96-3.08 3 3 0 0 1-.34-5.58 2.5 2.5 0 0 1 1.32-4.24 2.5 2.5 0 0 1 4.44-2.04" />
        <path d="M14.5 2A2.5 2.5 0 0 0 12 4.5v15a2.5 2.5 0 0 0 4.96.44 2.5 2.5 0 0 0 2.96-3.08 3 3 0 0 0 .34-5.58 2.5 2.5 0 0 0-1.32-4.24 2.5 2.5 0 0 0-4.44-2.04" />
      </svg>
    );
  }

  // 3. Orthopedics: Bone / Joint
  if (normalized.includes('ortho')) {
    return (
      <svg {...props}>
        <path d="M17 10c.7-.7 1.69-1 2.5-1a2.5 2.5 0 1 0-2.5-2.5c0 .81.3 1.8 1 2.5l-7 7c-.7-.7-1.69-1-2.5-1a2.5 2.5 0 1 0 2.5 2.5c0-.81-.3-1.8-1-2.5l7-7Z" />
        <circle cx="18" cy="6" r="2" />
        <circle cx="6" cy="18" r="2" />
        <path d="m14.5 9.5-5 5" />
      </svg>
    );
  }

  // 4. Dermatology: Skin / Sparkle care
  if (normalized.includes('derma')) {
    return (
      <svg {...props}>
        <path d="m12 3-1.9 5.8a2 2 0 0 1-1.3 1.3L3 12l5.8 1.9a2 2 0 0 1 1.3 1.3L12 21l1.9-5.8a2 2 0 0 1 1.3-1.3L21 12l-5.8-1.9a2 2 0 0 1-1.3-1.3L12 3Z" />
        <circle cx="12" cy="12" r="3" />
      </svg>
    );
  }

  // 5. Pediatrics: Child / Baby
  if (normalized.includes('pediatric')) {
    return (
      <svg {...props}>
        <circle cx="12" cy="8" r="5" />
        <path d="M20 21a8 8 0 0 0-16 0" />
        <path d="M9 7h.01" />
        <path d="M15 7h.01" />
        <path d="M10 10a2 2 0 0 0 4 0" />
      </svg>
    );
  }

  // 6. Gynecology: Women's health / Venus symbol
  if (normalized.includes('gynec') || normalized.includes('obstet')) {
    return (
      <svg {...props}>
        <circle cx="12" cy="9" r="6" />
        <path d="M12 15v7" />
        <path d="M9 19h6" />
      </svg>
    );
  }

  // 7. General Medicine: Medical cross / Stethoscope badge
  if (normalized.includes('general') || normalized.includes('internal')) {
    return (
      <svg {...props}>
        <path d="M9 3H5a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2V5a2 2 0 0 0-2-2h-4" />
        <rect x="9" y="2" width="6" height="4" rx="1" />
        <path d="M12 11v6" />
        <path d="M9 14h6" />
      </svg>
    );
  }

  // 8. Ophthalmology: Eye / Vision
  if (normalized.includes('ophthalm') || normalized.includes('eye')) {
    return (
      <svg {...props}>
        <path d="M2 12s3-7 10-7 10 7 10 7-3 7-10 7-10-7-10-7Z" />
        <circle cx="12" cy="12" r="3" />
      </svg>
    );
  }

  // 9. ENT (Otolaryngology): Ear / Head & Neck
  if (normalized.includes('ent') || normalized.includes('otolaryng')) {
    return (
      <svg {...props}>
        <path d="M6 8.5a6.5 6.5 0 1 1 13 0c0 6-6 6-6 10a3.5 3.5 0 1 1-7 0" />
        <path d="M15 8.5a2.5 2.5 0 0 0-5 0v2a2.5 2.5 0 0 0 5 0" />
      </svg>
    );
  }

  // 10. Psychiatry: Mind / Mental wellness
  if (normalized.includes('psychiat') || normalized.includes('mental')) {
    return (
      <svg {...props}>
        <path d="M12 2a7 7 0 0 0-7 7c0 2.38 1.19 4.47 3 5.74V17a2 2 0 0 0 2 2h4a2 2 0 0 0 2-2v-2.26c1.81-1.27 3-3.36 3-5.74a7 7 0 0 0-7-7Z" />
        <path d="M9 21h6" />
        <path d="M12 6v3" />
        <path d="M10.5 7.5l3 3" />
      </svg>
    );
  }

  // 11. Gastroenterology: Digestive tract / Stomach
  if (normalized.includes('gastro')) {
    return (
      <svg {...props}>
        <path d="M12 2v4" />
        <path d="M8 6h8c2 0 4 2 4 5s-1 6-4 8-6 3-8 3-4-2-4-5 1-7 4-11Z" />
        <path d="M12 11c1 1 1 3 0 4" />
      </svg>
    );
  }

  // 12. Endocrinology: Hormones / Thyroid pulse
  if (normalized.includes('endocrin')) {
    return (
      <svg {...props}>
        <path d="M12 2a5 5 0 0 0-5 5c0 3.5 5 9 5 9s5-5.5 5-9a5 5 0 0 0-5-5Z" />
        <path d="M9 18l3 4 3-4" />
        <circle cx="12" cy="7" r="1.5" />
      </svg>
    );
  }

  // 13. Urology: Urinary / Bladder
  if (normalized.includes('uro')) {
    return (
      <svg {...props}>
        <path d="M12 3a6 6 0 0 0-6 6c0 4 3 8 6 12 3-4 6-8 6-12a6 6 0 0 0-6-6Z" />
        <path d="M12 8v5" />
      </svg>
    );
  }

  // 14. Pulmonology: Lungs / Respiratory
  if (normalized.includes('pulmon') || normalized.includes('lung')) {
    return (
      <svg {...props}>
        <path d="M12 3v9" />
        <path d="M6 12c-2.5 0-4 2-4 5.5S4 21 7 21c3.5 0 4-3.5 5-7" />
        <path d="M18 12c2.5 0 4 2 4 5.5S20 21 17 21c-3.5 0-4-3.5-5-7" />
        <path d="M12 7l-3 3" />
        <path d="M12 7l3 3" />
      </svg>
    );
  }

  // 15. Nephrology: Kidneys
  if (normalized.includes('nephro')) {
    return (
      <svg {...props}>
        <path d="M12 5C7 5 5 8 5 12s2 7 7 7c2 0 3-1 3-3s-2-2-2-4 2-2 2-4-1-3-3-3Z" />
        <path d="M12 12h2" />
        <path d="M12 9v6" />
      </svg>
    );
  }

  // 16. Oncology: Ribbon / Cancer care
  if (normalized.includes('oncol') || normalized.includes('cancer')) {
    return (
      <svg {...props}>
        <path d="M12 3a4 4 0 0 0-4 4c0 3 4 7 4 7s4-4 4-7a4 4 0 0 0-4-4Z" />
        <path d="M9 14l-4 7" />
        <path d="M15 14l4 7" />
      </svg>
    );
  }

  // Default: Medical Stethoscope / Cross
  return (
    <svg {...props}>
      <path d="M19 14c1.49-1.46 3-3.21 3-5.5A5.5 5.5 0 0 0 16.5 3c-1.76 0-3 .5-4.5 2-1.5-1.5-2.74-2-4.5-2A5.5 5.5 0 0 0 2 8.5c0 2.3 1.5 4.05 3 5.5l7 7Z" />
    </svg>
  );
}
