# ISL Trace – quick-start pilot protocol

This is a lightweight Work-in-Progress pilot protocol. Obtain the required institutional ethics approval/clearance and approved participant consent before recruiting or collecting research data.

## Fixed study setup

- Adult participants (18+), using researcher-assigned IDs such as P001, P002, and P003. Do not enter participant names in the app.
- Use the same shared Android tablet for all participants.
- Conduct every study session in **landscape orientation** on the same stable tablet stand. Orientation is a normal application capability, not a study comparison.
- Use **all 15 signs** in `app/src/main/assets/signs.csv`. All 15 rows should remain `enabled=true`.
- Keep the same sign catalog, reference JSONs, videos, app build, model and scoring thresholds for every participant. Do not edit the catalog after the first participant begins.
- Keep the camera distance, tablet position and lighting as consistent as practical.

## Participant procedure

1. Explain the task, provide the approved study information sheet and obtain consent using the institution-approved process.
2. Assign/enter the participant's pseudonymous ID and record prior ISL experience.
3. Explain how to watch the reference, perform a sign, view feedback and move to another sign.
4. Ask the participant to practise all 15 signs at their own pace. They may repeat a sign as many times as they wish, take breaks, or move on when ready. Do not impose an attempt limit or time limit.
5. Do not coach participants about whether a sign is correct while they are performing the tasks.
6. When leaving a sign after at least one completed attempt, the app asks for a 1–5 difficulty rating and a 1–5 feedback usefulness/clarity rating. Participants may skip this prompt.
7. At the end, administer the standard 10-item System Usability Scale (SUS) separately using its original wording and scoring procedure, plus a few open-ended questions.
8. Record technical problems and any live observer assessments using participant ID, sign and attempt number only.

## Independent observer assessment

Because the study logger does not retain video or hand-landmark sequences, an independent ground-truth assessment is available only when a fluent ISL observer is present and can watch the attempt live. Use `OBSERVER_ASSESSMENT_TEMPLATE.csv` as a separate sheet. Suggested values are `CORRECT`, `PARTIAL`, `INCORRECT`, and `UNABLE_TO_JUDGE`.

If no fluent observer is available, do not invent ground truth. Report the app's classifier and trace outputs as **system-observed outcomes**, not as verified signing correctness. Do not record video or raw landmarks unless separately covered by ethics approval and consent.

## End the session and export

1. From the sign list, choose **Finish participant session**. This writes the completed session record and returns to the home screen.
2. On the home screen, choose **Researcher data export** and enter the researcher PIN.
3. Save the ZIP using Android's file picker. Export does not create a participant/session record and does not delete existing data.
4. If the save dialog is cancelled, return to **Researcher data export** and try again. No participant sign-in is needed.
5. Export after every participant (or at least every study day) and verify that the ZIP opens on a computer. Keep exports in institution-approved storage.

The researcher export PIN in this package is `1504`. Change `researcher_export_pin` in `app/src/main/res/values/strings.xml` before building if you need a different local access code. This is a basic in-app gate against accidental participant access, not strong security; keep the tablet under researcher supervision.

## Export contents and interpretation

The ZIP contains:

- `participants.csv`
- `sessions.csv`
- `attempts.csv`
- `interactions.csv`
- `sign_feedback.csv`
- `signs.csv` and `labels.txt` (the vocabulary/model-label snapshot)
- `README.txt`

All timestamps are UTC. Duration values are seconds. Attempts are grouped by `task_id`; multiple attempts for the same sign within one task share that ID.

- The three technique stars are calculated from path movement, hand shape and palm/back orientation. They remain independent of the app's recognition-model result.
- `classifier_status=ACCEPTED` means the app's recognition model ran and accepted the selected target under its existing thresholds.
- `classifier_status=REJECTED` means the model ran but did not accept the selected target. `UNAVAILABLE_NOT_LOADED`, `NOT_RUN_NO_RISE`, `NOT_RUN_INSUFFICIENT_INPUT`, and `ERROR` mean recognition was unavailable or not evaluated; do not count these as model rejections.
- The legacy `system_verdict` field retains the existing combined pass/retry result and can say `ACCEPTED` for a trace-only fallback. For recognition analysis, use `classifier_status`, not `system_verdict`.
- The three stars are not ground-truth judgements of whether a sign was correct.
- The pilot supports preliminary observations about usability, perceived sign difficulty, practice effort and interaction patterns. Do not claim improved long-term sign-language acquisition without an independent pre/post or retention assessment.

Keep the participant-to-ID key separate from exported app data. Follow the approved retention and deletion plan.
