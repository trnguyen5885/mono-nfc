# NFC parity checks

Run this matrix on a physical Android device and an iPhone using approved test
cards only. Store hashes and expected fields; never store raw MRZ, DG bytes or
face images in CI artifacts.

| Area | Android core + RN adapter | iOS core + RN adapter | Evidence required |
|---|---|---|---|
| PACE | valid CAN, invalid CAN | valid CAN, invalid CAN | Stable code/message and recording of outcome |
| Connection | tag lost, timeout, retry | session cancellation, timeout, retry | No pending Promise/session |
| Mandatory data | DG1 | DG1 | Normalized name, DOB, gender, nationality |
| Optional data | DG2, DG13, DG14, SOD | DG2, DG13, DG14, SOD | Hash/size and expected absence behavior |
| Image | JPEG/JPEG2000 MIME/size | JPEG/JPEG2000 MIME/size | MIME, size and no raw artifact |
| Cache | same-card hit, different-card miss, clear | same-card hit, different-card miss, clear | DG1/SOD fingerprint behavior |
| Progress | monotonic 0-100 | monotonic 0-100 | Ordered phase log without personal data |
| Privacy | no sensitive log | no sensitive log | Code review and test log review |
| DG15/AA | pending scope decision | pending scope decision | Comparison with Flutter reference if enabled |

The checkpoint is not passed until every in-scope row is verified on both
platforms. Simulator/emulator compile is not NFC evidence.
