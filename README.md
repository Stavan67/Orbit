# 🛰️ ORBIT — Satellite Conjunction Analysis System

> A real-time space collision risk assessment system for satellite operators, built using publicly available data from [Space-Track.org](https://www.space-track.org).

---

## Overview

**ORBIT** independently screens the entire catalogued space object population against a monitored satellite, detects potential close approaches using orbital mechanics, computes collision probability, and classifies events by risk level **without relying on any commercial Space Situational Awareness (SSA) service**.

---

## 🎯 Project Goal

ORBIT provides an independent, self-hosted conjunction screening capability using entirely public data for small satellite operators, universities running CubeSats and early-stage commercial operators with one to five satellites.

Commercial SSA services (LeoLabs, ExoAnalytic, SpaceFence) are expensive. Space-Track.org issues Conjunction Data Messages (CDMs) only for a monitored subset of object pairs. ORBIT fills the gap:

- Propagates the **entire public TLE catalogue** (~30,000+ objects) against a monitored satellite
- Independently identifies every geometrically plausible collision threat within a **7-day forward window**
- Uses CDMs from Space-Track to **upgrade Pc accuracy** where available

---

## 🏗️ System Architecture

ORBIT is structured as an 8-stage pipeline, from raw data ingestion to a served dashboard API.

```
Stage 0 → Stage 1 → Stage 2 → Stage 3 → Stage 4 → Stage 5 → Stage 6 → Stage 7 → Stage 8
Ingest    Filter     Scan     Refine    Compute   Classify  Fallback   Dedup     API
```

---

### Stage 0 — Data Ingestion

#### TLE Fetch (`SpaceTrackService`)
On startup and daily at **00:30 UTC**, the system authenticates with Space-Track.org and fetches the full Two-Line Element set catalogue (~30,000–30,200 records). Each TLE is parsed and stored in the `tle_data` table. Existing records are updated; new objects are created. Satellites with TLEs older than **30 days** are flagged as stale and excluded from screening.

#### CDM Fetch (`CdmIngestionService`)
Immediately after the TLE fetch, CDMs are fetched from Space-Track for the monitored satellite using the `cdm_public` endpoint. Only CDMs with TCA in the future are fetched (`TCA/>now`). Existing records (matched by `CDM_ID`) are skipped. Any ingested CDM with **Pc ≥ 1e-4** triggers a WARN-level log alert.

**CDM field mappings from Space-Track API:**

| API Field | Database Field | Notes |
|-----------|---------------|-------|
| `MIN_RNG` | `miss_distance_m` | Metres, stored directly — no unit conversion |
| `REL_SPEED` | `relative_speed_ms` | km/s from API, multiplied by 1000 to store as m/s |
| `PC` | `pc` | Dimensionless probability, stored as-is |

---

### Stage 1 — Candidate Filtering (`SatelliteFilterService`)

Before any propagation, objects that are geometrically impossible conjunction partners are eliminated. This reduces the screening population from **~30,000 to ~16,000–17,000** candidates.

| Filter | Description |
|--------|-------------|
| **Altitude** | Retains objects whose altitude range overlaps the primary's range ± 150 km |
| **Inclination** | Retains objects within 60° of the primary's inclination |
| **RAAN** | For non-polar primaries (inclination < 60°), eliminates objects with too-distant orbital planes. Skipped automatically for polar/SSO primaries (inclination ≥ 60°) |
| **Co-location** | Removes objects with essentially identical orbital elements (prevents artificial near-zero miss distances) |
| **Stale TLE** | Excludes objects with TLE epoch older than 30 days |

---

### Stage 2 — TLE Screening: Coarse Scan (`ConjunctionScreeningService`)

The primary satellite and each candidate are propagated forward over a **7-day window** using SGP4 via Orekit, sampling inter-satellite separation at every **30-second** time step.

At each step, Cartesian position vectors are computed in the **ECI J2000 frame**. If separation falls below the **150 km coarse scan threshold**, the pair is flagged for refinement.

**Why 30-second steps and 150 km threshold?**

At a typical LEO relative velocity of ~10 km/s, the worst-case position error between two samples is:

```
max_position_error = relative_velocity × (step_seconds / 2)
                   = 10,000 m/s × 15 s = 150 km
```

The 150 km threshold matches this worst-case error, ensuring no real close approach is ever completely missed at the coarse scan stage.

> **Note:** For very fast near-head-on geometries (~14–15 km/s), the worst-case error can reach ~200–225 km, exceeding the threshold. These events are caught by the **CDM-only fallback** (Stage 6).

---

### Stage 3 — TCA Refinement

For each candidate pair, a two-stage refinement locates the true **Time of Closest Approach (TCA)** with sub-second accuracy.

| Step | Time Step | Window | Result |
|------|-----------|--------|--------|
| **Fine refinement** | 1 second | ± 120 s around coarse minimum | Narrows error from 150 km → ~10–15 m |
| **Polish step** | 0.1 seconds | ± 10 s around fine minimum | Sub-second TCA accuracy, metre-level miss distance |

The refined TCA, miss distance (metres), and relative velocity (m/s) are passed to Stage 4.

---

### Stage 4 — Probability of Collision (`ProbabilityOfCollisionService`)

Implements the **Foster 2D Pc model** — the industry standard algorithm used by the US Space Surveillance Network and 18th Space Control Squadron.

#### When a CDM is available (`CDM_DIRECT` method)
The CDM provides an authoritative Pc computed by 18th SCS using real radar-derived covariance matrices. This value is used directly and **supersedes any TLE-based estimate**. The CDM is matched to the screening result by TCA proximity (within ± 6 hours).

#### When no CDM is available (`TLE_AGE_MODEL` method)
Position uncertainty (sigma) is estimated from TLE age using a statistical model derived from empirical TLE accuracy studies:

```
sigma_radial  = BASE_100m  + GROWTH_100m_per_day  × tle_age_days
sigma_cross   = BASE_300m  + GROWTH_200m_per_day  × tle_age_days
```

Fresh TLEs (age 0 days) start with ~100 m radial and ~300 m cross-track uncertainty, growing linearly with age.

**The Foster 2D formula:**

```
Pc = (HBR² / (2π × σ_x × σ_y)) × exp(-0.5 × ((x/σ_x)² + (y/σ_y)²))
```

Where:
- `HBR` = combined hard body radius of both objects (default 10 m)
- `x`, `y` = miss distance components in the conjunction plane
- `σ_x`, `σ_y` = combined (root-sum-square) position uncertainties of both objects

> **Pc clamping:** Values below 1e-30 are clamped to exactly 0.0. These represent genuine double-precision floating-point underflow and have zero operational significance.

---

### Stage 5 — Risk Classification (`RiskAssessmentService`)

Risk is assigned using **two independent criteria**; the more dangerous result is used as the final classification.

#### Pc-Based Classification

| Pc Range | Risk Level |
|----------|------------|
| Pc ≥ 1e-2 (1 in 100) | 🔴 **CRITICAL** |
| 1e-3 ≤ Pc < 1e-2 (1 in 1,000) | 🟠 **HIGH** |
| 1e-4 ≤ Pc < 1e-3 (1 in 10,000) | 🟡 **MEDIUM** |
| Pc < 1e-4 | 🟢 **LOW** |

#### Geometry-Based Classification

Acts as a safety net for events where TLE uncertainty causes the Pc model to underestimate risk. A 200 m miss distance at 15 km/s is operationally critical regardless of what an uncertainty-inflated Pc formula produces.

| Condition | Risk Level |
|-----------|------------|
| Miss distance < 1 km AND TCA within 24 hours | 🔴 **CRITICAL** |
| Miss distance < 2 km AND relative velocity > 12 km/s | 🔴 **CRITICAL** |
| Effective miss distance < 5 km AND TCA within 48 hours | 🟠 **HIGH** |
| Miss distance < 2 km | 🟠 **HIGH** |
| Effective miss distance < 10 km AND TCA within 72 hours | 🟡 **MEDIUM** |
| Otherwise | 🟢 **LOW** |

> The **effective miss distance** applies a velocity factor: high relative velocity events are treated as geometrically more dangerous due to shorter encounter durations.

The final risk level is the **higher (more dangerous)** of the Pc-based and geometry-based classifications.

---

### Stage 6 — CDM-Only Fallback

After the main screening loop completes, `buildCdmOnlyEvents` checks whether any available CDMs correspond to conjunction pairs that were **not detected** by TLE screening.

For each CDM group (grouped by deduplicated pair-hour key), if no matching event exists in the screening results and no existing event exists in the database for the same key, a new event is created directly from the CDM data and labelled `DetectionSource.CDM_ONLY`.

This catches geometrically hard cases: fast near-head-on encounters (~14–15 km/s) where the 30-second time step misses the event. Space-Track's high-fidelity tracking detects these and issues CDMs.

>  **CDM-only events are clearly labelled** in the database and dashboard. They represent conjunctions that this system's independent screening could not detect — they are real threats surfaced from authoritative external data, not independent detections.

---

### Stage 7 — Deduplication and Persistence (`ConjunctionDeduplicationService`)

Prevents the same conjunction from appearing multiple times across daily re-runs.

**Deduplication key format:**

```
{primaryNorad}_{secondaryNorad}_{tcaDateHour}

Example: 16181_56630_2026022715
```

This groups all CDM revisions for the same conjunction pass into a single event. When a new analysis run produces a result for an existing key:

- If the new event has a **more accurate Pc** (CDM-based over TLE-based), it updates the existing record
- If the existing record already has current data, it is skipped
- `DetectionSource.TLE_SCREENED` is **never downgraded** to `CDM_ONLY` — independent detections are preserved

---

### Stage 8 — Dashboard API

```
GET /api/conjunction/dashboard/{noradId}
```

Returns a structured JSON response containing all **CRITICAL**, **HIGH**, and **MEDIUM** events for the monitored satellite with TCA in the future. Events are:

- Grouped by risk level
- Enriched with human-readable display values (formatted Pc strings, hours-to-TCA, detection source labels)

This endpoint is consumed by the React dashboard served at the root URL.

---

## 🔐 Security

The system uses **JWT-based stateless authentication** with two built-in roles:

| Role | Permissions |
|------|-------------|
| `ADMIN` | All `GET`, `POST`, and `DELETE` endpoints |
| `OPERATOR` | `GET` endpoints only (read-only dashboard access) |

The dashboard (`/`, `/index.html`) and the login endpoint (`POST /api/auth/login`) are **publicly accessible** without authentication.

Credentials and the JWT secret are configured in `application.properties` and **must never be committed to version control**. For production use, set them as environment variables:

```bash
API_SECURITY_ADMIN_PASSWORD=<strong-password>
API_SECURITY_OPERATOR_PASSWORD=<strong-password>
JWT_SECRET=<minimum-32-char-random-string>
```

---

## 🏷️ Detection Sources and Honesty Labels

Every conjunction event carries a `detection_source` field that honestly describes how it was found.

| Badge | Label | Meaning |
|-------|-------|---------|
| 🟢 **TLE SCREENED** | `TLE_SCREENED` | Independently detected by this system's SGP4 propagation of public TLE data — purely by orbital mechanics, no CDM consulted. If a CDM is later matched to upgrade the Pc value, the source remains `TLE_SCREENED`; the independent detection is a permanent fact. |
| 🔵 **CDM CONFIRMED** | `CDM_DIRECT` | A CDM was matched to an independently screened event and used to upgrade its Pc to an authoritative radar-derived value. |
| 🟡 **CDM ONLY** | `CDM_ONLY` | Not detected by TLE screening — sourced entirely from a Space-Track CDM. Typically means the encounter geometry was too fast or head-on for the 30-second step to sample near the true TCA. The event is real and operationally significant; the label exists to be honest about system limitations, not to suggest it should be ignored. |

The dashboard displays these as visual badges: **TLE SCREENED** (green), **CDM CONFIRMED** (cyan), and **CDM ONLY** (amber).

---

## ⚠️ Limitations

**Fixed time step sampling gap** — The 30-second coarse scan step cannot guarantee detection of all conjunction geometries. For near-head-on encounters with relative velocity ≥ ~10 km/s, the closest approach can occur between two sample points. The CDM-only fallback mitigates this for pairs Space-Track monitors. Pairs not monitored by Space-Track and missed by TLE screening would be silently absent. A future improvement would replace the fixed step with Orekit's event detector API, which numerically solves for the exact minimum distance.

**TLE-based Pc is indicative, not authoritative** — When no CDM is available, Pc is computed from a statistical TLE-age uncertainty model, not real covariance matrices. The model gives a reasonable estimate of risk magnitude but should not be used as a basis for manoeuvre decisions without CDM confirmation.

**Single primary satellite** — The system screens one satellite at a time against the full catalogue. Full catalogue-vs-catalogue screening would require significantly more compute and is not the intended use case.

**Requires app to be running at scheduled time** — Schedulers are in-process Spring threads. If the application is not running at 02:00 UTC, the daily analysis is missed. For reliable daily operation, ensure the application is running continuously or trigger analysis manually.

---

## 🔬 Design Decisions & Industry Context

### Why ORBIT Does Not Use Covariance Matrices

The Foster 2D model mathematically requires a covariance matrix for each object, a matrix describing position and velocity uncertainty across all axes. For a system screening ~30,000 objects, that means 30,000 covariance matrices updated daily.

The problem is that **real covariance data for the full catalogue is not publicly available**. Space-Track's `cdm_public` endpoint includes covariance only for specific pairs it has already flagged as conjunction threats, not for the general catalogue. The full covariance catalogue is classified, accessible only to US government entities and vetted commercial operators under data-sharing agreements.

Even if it were available, covariance cannot be reconstructed from a TLE. TLEs are a compressed, averaged representation of a precise orbit determination solution, and the covariance information is deliberately discarded in that compression. The format was never designed to carry it.

**What ORBIT does instead** is the standard approach for academic tools and small operators: the TLE-age model estimates position uncertainty statistically from empirical studies of how SGP4 error grows with time since epoch. It is not wrong, it reflects realistic propagation uncertainty. It is simply less precise than a real covariance matrix. The resulting Pc values are correct in order of magnitude and appropriate for risk screening, which is why the system clearly labels them `TLE_AGE_MODEL` and defers to CDM Pc the moment authoritative data becomes available for a specific pair.

Every open-source conjunction tool faces this same constraint. This system handles it the same way the academic literature recommends.

---

### How Major Space Agencies Handle Conjunction Assessment

Major agencies operate at a fundamentally different level of the data stack — one that is largely inaccessible to the public.

**US Space Surveillance Network (SSN)** is the foundation everything else builds on. ~30 ground-based radar and optical sensors worldwide track every catalogued object multiple times per day. The internal product is a full state vector with a real covariance matrix for every object, updated continuously. TLEs are a lossy, publicly shareable *summary* of this data which is accurate to hundreds of metres to a few kilometres. SSN internal orbits are accurate to tens of metres for active payloads.

**18th Space Control Squadron** runs CAESAR (Conjunction Assessment Expert System for Advanced Results) at Vandenberg Space Force Base. It screens the full catalogue against all active payloads simultaneously using real covariance matrices, and generates CDMs automatically for any pair crossing the alert threshold. The CDMs on Space-Track.org are CAESAR outputs.

**NASA CARA** (Conjunction Assessment Risk Analysis) at Goddard performs a second layer of analysis for NASA missions, applying manual review and more sophisticated models. For high-value missions the threshold for beginning manoeuvre planning is Pc ≥ 1 in 100,000 which is far more conservative than the general 1 in 10,000 threshold and a Debris Avoidance Manoeuvre (DAM) is executed if Pc exceeds 1 in 1,000. The ISS performs several DAMs per year.

**ESA's ARES** system at ESOC runs independent conjunction assessment using SSN data provided under a data-sharing agreement, combined with ESA's own orbit determination for ESA satellites.

**Do they depend on TLE data?** No. TLEs are what they publish for public consumption. Internally they work with full state vectors and real covariances from their own sensor networks. SpaceX screens its Starlink constellation using onboard GPS-derived precise ephemerides which is far more accurate than TLEs and executes autonomous avoidance manoeuvres without human review for lower-risk events.

For a small operator with no access to classified or proprietary data, ORBIT's approach, full catalogue TLE screening with CDM upgrade where available is the **highest accuracy achievable from public data alone**. It is the same foundational approach used by commercial SSA companies like LeoLabs, except they supplement it with proprietary radar observations to generate their own covariance, which is exactly what they charge for.

---

## 📖 Glossary

<details>
<summary><strong>Orbital Mechanics &amp; Elements</strong></summary>

**Altitude (orbital)** — The height of a satellite above Earth's surface, measured in kilometres. For elliptical orbits, altitude varies between a minimum (perigee) and maximum (apogee). Computed as: semi-major axis − Earth's mean radius (6,371 km).

**Apogee** — The point in an elliptical orbit where a satellite is farthest from Earth's centre.

**Argument of Perigee (ω)** — One of the six Keplerian orbital elements. The angle measured in the orbital plane from the ascending node to the point of closest approach (perigee), in the direction of the satellite's motion. Defines where in the orbit the perigee occurs.

**Eccentricity (e)** — A dimensionless parameter describing the shape of an orbit. `e = 0` is a perfect circle; `0 < e < 1` is an ellipse (all Earth satellites); `e = 1` is a parabola; `e > 1` is a hyperbola. Low-Earth orbit satellites typically have eccentricities below 0.01.

**Epoch** — A reference time associated with a set of orbital elements. TLE epoch is the UTC date and time at which the TLE's mean elements are most accurate. Accuracy degrades with time elapsed from epoch due to unmodelled perturbations (atmospheric drag, solar radiation pressure, gravitational harmonics).

**Inclination (i)** — The angle between an orbital plane and Earth's equatorial plane, measured in degrees. 0° is equatorial (eastward). 90° is polar. 98–100° is sun-synchronous retrograde LEO. Values between 90° and 180° indicate retrograde orbits.

**J2 Perturbation** — The dominant non-spherical gravitational perturbation caused by Earth's equatorial bulge. Causes the orbital plane to precess (RAAN drift) and the argument of perigee to rotate over time. Modelled by SGP4.

**Keplerian Elements** — Six parameters that fully describe a Keplerian (two-body, unperturbed) orbit: semi-major axis, eccentricity, inclination, right ascension of the ascending node, argument of perigee, and true anomaly (or mean anomaly). Real orbits require propagation models like SGP4 for accuracy.

**Mean Anomaly (M)** — An angle that represents where a satellite is in its orbit as a fraction of the orbital period, measured from perigee. Unlike true anomaly, mean anomaly increases linearly with time, making it convenient for propagation.

**Mean Motion** — The number of complete orbits a satellite completes per day (revolutions per day). A value of 14.5 rev/day corresponds to an orbital period of about 99 minutes and an altitude of approximately 550 km.

**Orbital Period** — The time for a satellite to complete one full orbit. In LEO, typically 88–127 minutes. Related to mean motion by: `period (minutes) = 1440 / mean_motion (rev/day)`.

**Perigee** — The point in an elliptical orbit where a satellite is closest to Earth's centre.

**Perturbations** — Forces acting on a satellite in addition to Earth's central gravity. Includes atmospheric drag (dominant in low LEO), solar radiation pressure, lunar and solar gravity, and Earth's non-spherical gravity field.

**RAAN (Right Ascension of the Ascending Node)** — The angle measured in the equatorial plane from the vernal equinox direction to the point where the satellite's orbit crosses the equator going northward. Measured in degrees (0–360). RAAN drifts slowly over time due to J2 perturbations.

**Semi-major Axis (a)** — Half the longest diameter of an elliptical orbit. Determines the orbital period and mean altitude. For a circular orbit, equals the orbital radius (altitude + Earth's radius).

**True Anomaly** — The actual angle in the orbital plane from perigee to the satellite's current position, measured at Earth's centre. Accounts for the varying speed of a satellite in an elliptical orbit.

**Vernal Equinox** — The direction from Earth toward the Sun at the moment of the March equinox. Used as the reference direction (0° right ascension) for the ECI coordinate system and for measuring RAAN.

</details>

<details>
<summary><strong>Orbit Types &amp; Regimes</strong></summary>

**LEO (Low Earth Orbit)** — Orbits with altitudes roughly between 200 km and 2,000 km. The vast majority of operational satellites and debris are in LEO. Orbital periods are approximately 90–127 minutes. Relative velocities between crossing objects can reach 14–15 km/s.

**Polar Orbit** — An orbit with inclination near 90°, passing approximately over Earth's poles on each revolution. Polar satellites cross all latitudes and all RAAN values, meaning their ground tracks cross those of objects in any orbital plane — hence the RAAN filter is disabled for polar primaries.

**Sun-Synchronous Orbit (SSO)** — A near-polar orbit (~98° inclination) where the orbital plane precesses at the same rate as Earth's revolution around the Sun, maintaining a constant angle between the orbital plane and the Sun. Preferred for Earth observation satellites.

</details>

<details>
<summary><strong>Conjunction Analysis &amp; Probability</strong></summary>

**CDM (Conjunction Data Message)** — A standardised data product issued by 18th Space Control Squadron (US Space Force) via Space-Track.org when two tracked objects are predicted to pass within a defined proximity threshold. Contains TCA, miss distance, relative velocity, covariance matrices, and computed Pc. CDMs are revised multiple times as TCA approaches and tracking data improves.

**Coarse Scan** — The first pass of TLE screening, propagating all candidate pairs at 30-second time steps and flagging any step where separation falls below 150 km.

**Covariance Matrix** — A matrix describing the statistical uncertainty in a satellite's position and velocity. Diagonal elements are variances in each coordinate axis; off-diagonal elements are correlations. Real covariance data from radar tracking is far more accurate than the TLE-age statistical model.

**Effective Miss Distance** — In risk classification, the raw miss distance divided by a velocity factor. High relative velocity encounters are treated as effectively closer because the shorter encounter duration reduces natural divergence opportunity.

**Foster 2D Model** — The standard collision probability algorithm used by the US Space Surveillance Network. Projects both objects' position uncertainty ellipsoids onto the conjunction plane and integrates the overlap probability over the combined hard body radius. Named after John Foster of NASA JSC.

**Hard Body Radius (HBR)** — The physical radius of a space object used in Pc calculations. The combined HBR is the sum of both objects' radii. Default value in ORBIT is 10 metres.

**Miss Distance** — The minimum separation distance between two objects at their closest approach (TCA), measured in metres. Also called minimum range (`MIN_RNG`) in CDM terminology.

**Pc (Probability of Collision)** — A dimensionless number between 0 and 1 expressing the probability that two objects will physically collide during a specific conjunction pass. Values of 1e-4 (1 in 10,000) are typically the threshold at which satellite operators begin considering manoeuvres. Values above 1e-2 are extremely rare and represent emergency situations.

**Relative Velocity** — The speed of one object relative to another at the moment of closest approach. In LEO, ranges from near zero (co-planar objects in the same direction) to ~15 km/s (near-head-on, crossing orbits).

**Risk Level** — ORBIT's four-tier classification: CRITICAL, HIGH, MEDIUM, LOW. Determined by combining Pc-based and geometry-based assessment, taking the more dangerous of the two results.

**Screening Epoch** — The UTC timestamp at which a conjunction analysis run begins. All risk assessments within a single run reference the same screening epoch for hours-to-TCA and TLE age calculations.

**Sigma (σ)** — Standard deviation of position uncertainty. In the conjunction plane, σ_x and σ_y represent the one-sigma uncertainty in the radial and cross-track directions respectively. A larger sigma means the object's true position is more uncertain relative to the propagated position.

**TCA (Time of Closest Approach)** — The specific UTC time at which two objects reach their minimum separation distance during a conjunction pass. Reported to millisecond precision in CDMs.

</details>

<details>
<summary><strong>Data Sources, Labels &amp; Identifiers</strong></summary>

**CDM_DIRECT** — The Pc computation method label used when Pc is taken directly from a Space-Track CDM. More accurate than `TLE_AGE_MODEL` because it uses real radar-derived covariance.

**CDM_ONLY** — A detection source label indicating a conjunction event was not independently detected by TLE screening. The event was sourced entirely from a Space-Track CDM.

**Debris** — Non-functional, non-manoeuvrable space objects including rocket bodies, fragmentation debris from explosions and collisions, and decommissioned satellites. Cannot manoeuvre to avoid collisions.

**Deduplication Key** — A string combining primary NORAD ID, secondary NORAD ID, and TCA truncated to the hour (e.g. `16181_56630_2026022715`). Prevents the same conjunction pass from creating multiple database records across daily analysis runs.

**NORAD ID (NORAD Catalog Number)** — A unique integer assigned to every tracked space object by the US Space Surveillance Network. The primary identifier in TLE sets and Space-Track data products.

**Propagation** — The mathematical process of predicting a satellite's position and velocity at a future or past time, given its current orbital elements and a force model. SGP4 is the standard analytical propagator for TLE-based orbits.

**Satellite Catalogue** — The publicly available list of all tracked Earth-orbiting objects maintained by the US Space Surveillance Network and published via Space-Track.org. Currently ~30,000+ objects, down to approximately 10 cm in diameter in LEO.

**SGP4 (Simplified General Perturbations 4)** — The standard analytical propagation model for Earth-orbiting objects. Accepts a TLE as input and predicts position and velocity at any time near the TLE epoch. Models J2/J4 gravitational harmonics, atmospheric drag, and solar radiation pressure. Accuracy typically degrades to 1–10 km error after 3–7 days depending on orbital regime and solar activity.

**Space-Track.org** — The official US government website operated by 18th Space Control Squadron (US Space Force) providing public access to the satellite catalogue, TLE data, CDMs, and other SSA products. Free registration required.

**SSA (Space Situational Awareness)** — The knowledge of the orbital environment — positions, velocities, and predicted trajectories of all tracked space objects — required to operate satellites safely. Commercial SSA providers include LeoLabs, ExoAnalytic, and LeoSat.

**TLE (Two-Line Element Set)** — A standardised format for encoding the orbital elements of an Earth-orbiting object in two 69-character lines. Contains mean motion, eccentricity, inclination, RAAN, argument of perigee, mean anomaly, TLE epoch, and drag terms. The input to SGP4 propagation.

**TLE_AGE_MODEL** — The Pc computation method label used when no CDM is available. Pc is computed from the Foster 2D model with sigma values estimated statistically from TLE age. Less accurate than `CDM_DIRECT` but provides an operational risk estimate for unmonitored pairs.

**TLE_SCREENED** — A detection source label indicating a conjunction event was independently detected by this system's SGP4 propagation of public TLE data, without consulting any CDM.

</details>

<details>
<summary><strong>Coordinate Systems</strong></summary>

**ECI (Earth-Centred Inertial)** — A coordinate frame with origin at Earth's centre, axes fixed relative to distant stars (not rotating with Earth). Used for orbital mechanics calculations. The J2000 frame is a specific ECI frame referenced to the orientation of Earth's equator and equinox at noon on 1 January 2000.

</details>

---

## 🤝 Acknowledgements

- Orbital data provided by [Space-Track.org](https://www.space-track.org)
- Orbital propagation via [Orekit](https://www.orekit.org) (SGP4)
- Pc computation based on the **Foster 2D model** as used by the 18th Space Control Squadron
