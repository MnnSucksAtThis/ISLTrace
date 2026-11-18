# ISL Trace

A learner-paced Android application for practising Indian Sign Language signs using on-device hand tracking, a reference trace guide and a TFLite recognition model.

The application supports portrait and landscape. For the research pilot, use landscape for every participant on the same fixed tablet stand; orientation is not being evaluated as a study factor.

## Recognition and scoring

The existing MediaPipe hand-tracking pipeline, reference representation, TFLite classifier, classifier thresholds and TraceScorer tolerances are retained.

The result screen presents three technique stars for path movement, hand shape, and palm/back orientation. A separate line reports whether the app's recognition model recognised the selected sign, did not recognise it, or could not evaluate the attempt. The technique stars are not removed when the recognition model rejects or cannot evaluate an attempt. Existing recognition and scoring thresholds are unchanged.

## Editable sign list and reference media

The learner-facing sign catalog is `app/src/main/assets/signs.csv`. It controls which signs appear, their display names, the reference JSON used by the guide, and the demonstration video used by the Video button.

All 20 current model classes are enabled by default. The catalog is validated at load time against `labels.txt` and the referenced assets. A bad label or missing file produces a clear catalog error instead of silently making the sign impossible to recognise.

See [CONTENT_CATALOG.md](CONTENT_CATALOG.md) for how to reorder or remap signs and media. The current classifier supports the 20 classes in `app/src/main/assets/labels.txt`; a genuinely new model class requires a retrained model and matching labels.

## Research pilot logging

The app uses researcher-assigned pseudonymous participant IDs, using formats such as P001, P002, and P003. On each study visit, the participant enters their ID and prior ISL experience, confirms that they have received and agreed to the approved study information, and selects their signing hand.

The app stores local metadata only:

- Participant and session records
- Foreground session duration
- Sign attempts and existing trace/classifier outputs
- Reference-video, playback-speed, retry and orientation interactions
- Per-sign difficulty and feedback-usefulness ratings

The study logger does not save camera images, video, or landmark sequences. Data are stored in the application's private `research_data` directory. After finishing a participant session, the researcher can export from the home screen using **Researcher data export**, protected by a configurable local PIN. This export path does not require a participant sign-in, create a session, or delete existing data.

The researcher export PIN is `1504`; change `researcher_export_pin` in `app/src/main/res/values/strings.xml` before building if required. The PIN is a basic access gate, not strong security. Keep the shared tablet under researcher supervision.

The export contains `participants.csv`, `sessions.csv`, `attempts.csv`, `interactions.csv`, `sign_feedback.csv`, the vocabulary/label snapshot and a README. See [USER_STUDY_QUICK_START.md](USER_STUDY_QUICK_START.md) for the pilot procedure and data interpretation notes.

The in-app consent checkbox records participant confirmation; it does not replace the institution-approved participant information sheet or consent process. Obtain required institutional ethics approval/clearance before recruiting or collecting study data.

## Code guide

- `TraceScorer.kt`: existing trace measurements, thresholds and pass/fail fallback
- `SignClassifier.kt`: existing TFLite sign-recognition model
- `PracticeActivity.kt`: camera practice, trace evaluation and learner feedback
- `StudyLogger.kt`: local metadata-only CSV logging and ZIP export
- `ISLTraceApplication.kt`: foreground-duration tracking
- `SignCatalog.kt`: validates and reads the editable sign catalog
- `app/src/main/assets/signs.csv`: sign-to-reference/video mapping

## Build and tests

Open the project in Android Studio using its configured Android SDK/Gradle environment. The existing unit tests are run with:

```bash
./gradlew :app:testDebugUnitTest
```

Device camera and model tests require an Android device or emulator with camera support. Before the first participant, perform a complete dry run on the actual study tablet, including participant sign-in, several signs, feedback, finishing a session, researcher-only export, and opening the ZIP on a computer.


## ISL Trace 15 variant

This build contains 15 practice signs. Onion, Sports, Laptop, Computer and Sofa have been removed from the sign catalog and their reference/video assets. The original 20-class TFLite model and its 20-label `labels.txt` are intentionally retained so the model output indices remain aligned; only the 15 catalogued signs are available to learners. This variant has a separate Android application ID (`com.example.islteachingapp.trace15`) and app data storage from ISL Trace.
