# Feature requests

Proposed changes to what JPlateSolve sends to the solvers and how it solves. How the code is organised is in [DESIGN-IMPROVEMENTS.md](DESIGN-IMPROVEMENTS.md). Application features are in SpacePixels' [feature requests](../SpacePixels/FEATURE-REQUESTS.md).

A request is a proposal until the maintainer decides on it. When one is implemented, describe it in [README.md](README.md), remove it from this file, and make the SpacePixels follow-up listed under it.

| ID | Request | Priority |
|---|---|---|
| [JP-03](#jp-03-send-only-the-constraints-the-caller-set) | Send only the constraints the caller set, and parse sexagesimal signs | Medium |
| [JP-06](#jp-06-let-a-known-field-shorten-the-search) | Let a known field shorten the search, and size timeouts for large frames | Medium |

## JP-03. Send only the constraints the caller set

**Evidence.** `SubmitFileRequest` stores coordinates, radius, scale and `positional_error` as primitive `float`s, whose default is zero, and Gson writes those zeros. A request that sets only a session and a radius of 10 is sent with `center_ra` 0, `center_dec` 0, `radius` 10 and `positional_error` 0. The official client omits a field it was not given, and `positional_error` defaults to 1 on the server when absent.

`AstrometryDotNet.customSolve` treats a center of zero as "not set" and fills it from `OBJCTRA` and `OBJCTDEC`, the only header keywords read. The parser accepts three numbers separated by spaces. For a declination of `-0 30 00` or `-00 30 00`, the degree parses as negative zero, the sign test fails, and the result is +0.5° instead of −0.5°. Right-ascension seconds use the factor 0.00417 instead of 15/3600.

SpacePixels sends scale 0.1–180°, downsample 2 and radius 10, without a center. A frame without `OBJCTRA` and `OBJCTDEC` is therefore uploaded with a center at RA 0h, Dec 0°. A live test of the M5 frame still solved, more slowly (26.4 s instead of 15.7 s), so the server did not confine the search to the origin. That depends on server behaviour this library does not control.

**Proposal.**
- Record which fields the caller set, and send only those. A caller-supplied 0 is kept.
- A missing pointing header means a blind solve: send no `center_ra`, `center_dec` or `radius`. Do not combine one parsed coordinate with an invented zero.
- Parse sexagesimal with an explicit sign, including negative zero, and accept the colon form. Use 15/3600 for right-ascension seconds.
- Reject invalid explicit values with a clear error. A malformed header hint falls back to a blind solve.
- Build the submitted copy inside the client, leaving the caller's request unchanged.
- Default `publicly_visible` to `n` on `blindSolve`.
- Keep the primitive getters and add presence methods, or introduce a new request type, so compiled callers keep working.

**Acceptance.**
- A unit test of the JSON body: an omitted center and radius are absent; an explicit 0 is present; `positional_error` is absent unless set.
- Header fixtures: `12 34 56.7`, `12:34:56.7`, `-00 30 00.0`, `-00 00 30`, a missing keyword, repeated whitespace, NaN and invalid scale bounds. The southern fixture stays negative.
- A reused request object is unchanged after a solve.

**SpacePixels follow-up.** Send a radius only together with a center the library parsed, and keep `publicly_visible=n` (`PlateSolveService`).

## JP-06. Let a known field shorten the search

**Evidence.** Every ASTAP run is `astap -f <image> -r 360 -z 0 -fov 0 -wcs -annotate`. `-r 360` asks for a whole-sky search even when the header has a position. `-fov 0` stops ASTAP from reading the field of view from the header. `-annotate` writes a full-size JPEG that SpacePixels deletes unseen. On the HTTP side, the per-request timeout (default 60 s) is also the upload budget. A 61-megapixel frame is about 120 MB, so a slow link can exhaust 60 s while the 15-minute solve budget is untouched. The README says the library reads focal length and similar headers to speed up the solve; it does not.

**Proposal.**
- Make radius, RA, Dec, field of view and annotation parameters of the ASTAP call. By default, omit `-r`, `-fov` and `-annotate`, so ASTAP uses its own header rules, and keep `-z 0 -wcs`.
- Pass a modest radius and `-fov` only when the caller or the header supplies a center and a scale, and record the values in the result.
- Mind the units at the backend boundary: ASTAP's `-ra` is in hours, `-spd` is declination plus 90°, and `-fov` is the image height in degrees.
- Split the HTTP timings into connect, upload and status timeouts, alongside the overall solve budget. The upload default must cover a large FITS file.
- Optionally derive `scale_lower` / `scale_upper` from focal length and pixel size when the caller set no scale.
- Correct the README sentence about headers.

**Acceptance.**
- A command-capture test: the default FITS launch has no `-r 360` and no `-annotate`, and explicit values appear when requested.
- A loopback HTTP stub: an upload longer than the status timeout completes within the upload budget, and a connect failure still fails promptly.
- One real frame, solved with and without a hint, with wall times recorded, before SpacePixels' defaults change.

**SpacePixels follow-up.** Adopt a hinted ASTAP command and a longer upload budget after the timings are written down.
