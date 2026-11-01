# Feature requests

Proposed solver functionality for JPlateSolve, coordinated with SpacePixels. Current behavior is described in [README.md](README.md); design work is in [DESIGN-IMPROVEMENTS.md](DESIGN-IMPROVEMENTS.md).

A request is a proposal until the maintainer decides on it. When implemented, describe the result in README.md and remove the item here. The completed blind-solve scope of JP-03 and JP-06 is described in the README's [JP-03](README.md#jp-03-make-online-blind-solve-requests-unambiguous) and [JP-06](README.md#jp-06-improve-blind-solve-reliability-and-remove-unused-annotations) sections.

| ID | Request | Status |
|---|---|---|
| [JP-15](#jp-15-custom-solving-in-jplatesolve-and-spacepixels) | Custom solving in JPlateSolve and SpacePixels | Deferred; a fresh design is required |

## JP-15. Custom solving in JPlateSolve and SpacePixels

**Decision (2026-11-01).** JPlateSolve and SpacePixels currently provide blind solving only. Re-evaluate custom/near solving as a separate feature at a later stage, including the library API and application controls together. The previous custom request API and automatic FITS-hint parsing are removed; they are not a compatibility layer or the accepted design for this feature.

**Future scope to discuss.** Decide which caller-supplied position, radius and scale constraints are useful, whether trustworthy header hints should be offered, and how the user chooses between a hard restriction and a hint with a blind fallback. Define paired-coordinate precedence, exact zero, units, signed sexagesimal parsing, finite/range validation, optical metadata and binning provenance, immutable requests, diagnostics and one shared operation deadline before implementing either backend. Account for different ASTAP and online semantics, including upstream treatment of zero coordinates. Preserve private uploads, cancellation and owned cleanup.

**SpacePixels follow-up.** [F20](../SpacePixels/FEATURE-REQUESTS.md#f20-custom-plate-solving) tracks the corresponding future application controls. Neither project enables or implements this request today.
