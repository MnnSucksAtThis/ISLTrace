# Editing the ISL Trace sign list and reference media

The learner-facing sign list is controlled by:

`app/src/main/assets/signs.csv`

You can edit this file without changing Kotlin code, then rebuild and reinstall the app.

## Columns

```csv
display_name,classifier_label,reference_asset,video_asset,enabled
```

- `display_name`: label shown in the word list and practice screen.
- `classifier_label`: corresponding class name in `app/src/main/assets/labels.txt`. It must match an existing model label (case-insensitive).
- `reference_asset`: JSON landmark reference used by the tracing guide. This file must exist under `app/src/main/assets/`.
- `video_asset`: MP4 demonstration shown by the Video button. Leave blank to hide/disable this button, or provide an existing MP4 asset path.
- `enabled`: `true` shows the sign; `false` hides it from the learner list.

The row order is the learner-facing list order. Move rows to reorder signs. The default file enables all 20 current model classes in the original `labels.txt` order.

Example:

```csv
Laptop,Laptop,reference/laptop.json,videos/laptop.mp4,true
```

## Validation performed by the app

When the sign list opens, the app checks:

- The CSV header and five-column row format.
- Non-empty, unique display names and classifier labels.
- Every classifier label exists in `labels.txt`.
- Every required reference JSON exists and has a `.json` extension.
- Every non-empty video path exists and has an `.mp4` extension.
- Asset paths do not contain parent-directory traversal.
- `enabled` is exactly `true` or `false`, and at least one sign is enabled.

If validation fails, the app displays a catalog error and does not silently proceed with a broken sign list.

## To replace a reference video

1. Copy the replacement MP4 into `app/src/main/assets/videos/`.
2. Change the `video_asset` path in `signs.csv` to the new file.
3. Rebuild and install the app.

## To replace a tracing reference

1. Generate the reference JSON using `tools/extract_reference_landmarks.py`.
2. Copy the JSON into `app/src/main/assets/reference/`.
3. Point `reference_asset` in `signs.csv` to that JSON.
4. Rebuild and install the app.

## To change the study vocabulary

Set `enabled` to `true` for included signs and `false` for excluded signs. Keep exactly the same catalog for every participant in one study run; do not change the sign set after P001 begins.

For the current pilot, the agreed setup is to use **all 20 signs**, so leave all 20 rows set to `true`.

**Classifier limitation:** the current TFLite model has the 20 classes in `labels.txt`. Adding a genuinely new classifier class requires a newly trained model and matching `labels.txt`; changing `signs.csv` alone cannot teach the model a new word. You can replace or update references and videos for the existing classes.

Keep asset paths relative to `app/src/main/assets/`. Do not put commas in field values. The app validates the catalog when the sign list loads.
