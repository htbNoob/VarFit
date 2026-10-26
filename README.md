# VarFit — light-curve fitter for Seestar photometry

Android app that opens the photometry CSV exported by a ZWO Seestar, fits variable-star and
exoplanet-transit models (ROOT-style fit panel with fixed/limited parameters, automatic model
comparison and 2D χ² maps), and writes files you can upload to the AAVSO.

No third-party libraries: plain Kotlin + Android views, so the APK is small and the build is simple.

## Getting the APK (GitHub Actions)

1. Create a GitHub repository and upload the **contents** of this folder (so that `app/`,
   `gradle/`, `build.gradle.kts`, … are at the top level).
   - Check that `.github/workflows/build.yml` arrived. Some file browsers hide folders starting
     with a dot; if it is missing, create it on GitHub with *Add file → Create new file* and paste
     the content.
2. Open the *Actions* tab. The workflow **Build APK** runs on every push to `main`
   (unit tests first, then the APK).
3. When it is green, open *Releases* → **Latest build** and download `VarFit-1.0.N.apk` on the phone.
   Tagging a commit `v1.2.0` creates a normal release `VarFit-1.2.0.apk` instead.
4. Install it (allow "install unknown apps" for your browser/file manager once).

Every build is signed with the same key, so new builds install as updates over old ones and keep
your settings.

### Signing key

`signing/varfit-public.keystore` (password `varfit-public`, alias `varfit`) is a key generated for
this project and committed on purpose, so builds work without any setup. For a public repository
you may prefer a private key: create a keystore, base64-encode it and add the repository secrets
`SIGNING_KEYSTORE_BASE64`, `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS` (and optionally
`SIGNING_KEY_PASSWORD`). The workflow then uses it automatically. Note that switching keys means
uninstalling the old build once.

## Using the app

**Data** – *Open CSV…* (or share the CSV to VarFit from a file manager). The Seestar format is
recognised automatically; generic CSVs with a JD/HJD/BJD and a magnitude column also work.
Quality filter (SNR, number of reference stars, error), tap points to exclude them, optional binning
for fitting. *Load example* opens a DY Peg run (δ Scuti, 25 Oct 2026).

**Target** – choose the kind of variable (δ Scuti/HADS, RR Lyr, Cepheid, LPV, eclipsing binary,
exoplanet transit, other) and the event to time. *VSX near position* uses the coordinates from the
plate-solved CSV and fills type, period and epoch from the AAVSO VSX; *Exoplanets near position*
fills period, Tc, depth, duration, a/R*, inclination and limb darkening from the NASA Exoplanet
Archive. Optionally enter your location so airmass can be computed.

**Fit** – the fit panel:
- choose a fit function, edit start values, fix parameters, set limits (like ROOT's FitPanel);
- *Fit*: Levenberg–Marquardt, χ², ndf, χ²/ndf, BIC, AIC, residual rms, residual plot;
- *Auto-fit all models*: fits every model that fits the star class, each with a 2D grid search
  (profile likelihood) to find the global minimum, and ranks them by BIC;
- *2D χ² map*: scans any two parameters; with *profile* all other free parameters are re-fitted
  at each grid point. Contours at Δχ² = 2.30/6.18/11.8 (1/2/3σ for two parameters);
- *Periodogram* (generalised Lomb–Scargle) and *Kwee–van Woerden* for symmetric minima/maxima;
- derived values: time of maximum/minimum/mid-transit with uncertainty, O−C against the
  ephemeris, amplitude/depth, duration, …

**Export** – AAVSO files and the fit report.

## Models

| Model | Use | Parameters |
|---|---|---|
| Constant + trend | null model for comparison | m0, slope |
| Polynomial extremum (deg 2–5) | time of max/min from a short run | m0, **T0**, c2…cn |
| Fourier series (1–6 harmonics) | pulsators, W UMa | m0, **T0**, P, A1, A2, φ2, … |
| Gaussian + trend | simple peak or dip | m0, slope, **T0**, A, σ |
| Mikulášek eclipse profile | eclipsing-binary minima | m0, slope, **T0**, A, D, Γ |
| Transit: trapezoid | quick transit time and depth | m0, slope, kX, **T0**, δ, T14, T12 |
| Transit: limb-darkened | standard transit model (quadratic LD, circular orbit) | m0, slope, kX, **T0**, Rp/R*, a/R*, b, P, u1, u2 |

T0 is the event time. For the Fourier series the maximum is located numerically and its error
follows from Monte-Carlo sampling of the covariance matrix.

### Why BIC and not plain χ²

More parameters always lower χ², so the lowest χ² would always pick the most complex model.
BIC = χ² + k·ln n adds a penalty per free parameter; ΔBIC > 6 is strong evidence for the better model.
The ranking shows χ²/ndf too.

### Error bars

The Seestar's `Mag(err)` values are typically about twice the real scatter (χ²/ndf ≈ 0.25 for the
DY Peg example). With *Scale errors by √(χ²/ndf)* (default) parameter errors follow the actual
scatter of the data.

## AAVSO files

**Variable stars – AAVSO Extended Format** (WebObs → file upload): `#TYPE=EXTENDED`, JD or HJD,
`MTYPE=STD`, `CNAME=ENSEMBLE`, `CMAG=na`, as the format specifies for ensemble photometry. Before
uploading:
- enter your observer code and use the AAVSO/VSX star name (the Seestar writes "DY Pegasi", VSX
  uses "DY Peg"; the VSX lookup fills it in);
- choose the filter code that matches how your magnitudes were calibrated (`TG` for the green
  channel with V comparison magnitudes, `CV` for unfiltered with V);
- AAVSO asks for a check star (KNAME/KMAG) with ensemble photometry. The Seestar CSV does not
  contain one, so enter it yourself if you have it;
- describe the comparison stars in NOTES/CHART if you did not use an AAVSO sequence.

**Exoplanets – AAVSO Exoplanet Database format**: `#TYPE=EXOPLANET`, `BJD_TDB`, raw normalised flux
(`Rnflux`) with airmass as detrend column, priors and fit results in `#PRIORS`/`#RESULTS`. WebObs
also needs a plate-solved field image showing target and comparison stars.

**Fit report**: plain text with model, χ², parameters, event time and O−C (also useful for timing
databases such as BAV or the AAVSO EB section).

## Accuracy notes

- Times: the Seestar CSV's readable time is local time; VarFit uses the JD columns (UTC,
  mid-exposure) and converts to HJD (UTC) or BJD (TDB). Verified against astropy to < 0.1 s.
- A single short run (e.g. 1 h on a 1.75 h δ Scuti) gives an excellent time of maximum but not a
  period; amplitude from a partial cycle depends on the catalog period.
- Transits: with ~0.03 mag per 30 s frame, depths of a few percent (hot Jupiters) are measurable,
  sub-percent transits need binning and luck. a/R* and b are fixed to catalog values by default.
- The CSV holds only the target's final magnitudes, so detrending is limited to a linear trend and
  airmass.

## Project layout

```
app/src/main/java/com/varfit/core/   fitting core (pure Kotlin, unit-tested)
  Data.kt          CSV import, quality filter, binning, fit data
  TimeSystems.kt   JD → HJD/BJD, airmass
  Models.kt        fit functions, transit model
  Fitter.kt        Levenberg–Marquardt with fixed parameters and limits
  Chi2Map.kt       2D χ² maps, automatic model comparison
  Analysis.kt      event times, Monte-Carlo errors, periodogram, Kwee–van Woerden
  AavsoExport.kt   AAVSO Extended + Exoplanet formats, fit report
app/src/main/java/com/varfit/app/    Android UI (Data, Target, Fit, Export pages, plots, catalogs)
app/src/test/                        unit tests (run in CI before every build)
```

Local build: Android Studio (open the folder) or `./gradlew assembleRelease` with JDK 17 and the
Android SDK.
