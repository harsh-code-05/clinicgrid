# LGD district directory

Ministry of Panchayati Raj, 2022, Local Government Directory (LGD) - Districts, Open Government Data Platform India, resource updated 07/11/2026, [official dataset](https://data.gov.in/resource/local-government-directory-lgd-districts). Published under the [Government Open Data License - India](https://data.gov.in/Godl). The ministry does not endorse HMS.

The bundled snapshot contains all 785 district records in the official export. The OGD API reported a total of 785 records and an update timestamp of `07-11-2026 04:23:29`. All district codes are unique and cover all 36 states and union territories. The [live LGD directory](https://lgdirectory.gov.in/) reported 790 districts when this snapshot was prepared. This snapshot preserves the published export, which can differ from the live directory between refreshes.

| Artifact | SHA-256 |
| --- | --- |
| Official CSV downloaded on 2026-11-07 | `b8901c98350a4057d3371ce14228181e4bb12cc447f847490efee599a0e38b59` |
| `src/main/resources/locations/lgd-districts.tsv` (generated UTF-8 with LF line endings) | `5ed6c5c100395ecddffaff80c0f54c58e1e987269c4cb3e263d30004482f940f` |

## Refreshing the snapshot

1. Download the all-India CSV from the official dataset page. If its static download is unavailable, use the [official OGD API](https://data.gov.in/apis/37231365-78ba-44d5-ac22-3deec40b9197) for resource `37231365-78ba-44d5-ac22-3deec40b9197` with `format=csv`, `offset=0`, and `limit=all`. Confirm the update date and expected row count against the API metadata (`format=json`, `limit=1`, fields `updated_date` and `total`). Keep any OGD API key out of the repository.
2. Run the import from the repository root. Substitute the new download path and count for a later release.

   ```powershell
   .\scripts\import-lgd-districts.ps1 -InputCsv 'C:\Downloads\lgd_districts.csv' -ExpectedDistrictCount 785
   Get-FileHash 'C:\Downloads\lgd_districts.csv' -Algorithm SHA256
   Get-FileHash 'src\main\resources\locations\lgd-districts.tsv' -Algorithm SHA256
   ```

3. Review district additions, removals and renames by LGD code, then update the coverage, date and hashes in this document alongside the TSV. The importer retains the published English names and LGD codes, sorts by district code and writes UTF-8. It validates coverage and rejects malformed records and duplicate district codes before writing the snapshot.

HMS loads the snapshot from the application classpath. Refreshing it requires a new application build and does not require a database migration or a live call to LGD at runtime.
