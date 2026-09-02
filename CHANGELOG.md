# Changelog

All notable changes to Astrolabe Runtime for Android are documented here.

## 2.3.0

- Added ImageView rendered bounds, visible bounds, crop-to-padding state, and
  overflow inspection so tooling can report how image content is actually drawn.
- Added bounded current-state inspection for state-list, ripple, inset, and
  layered drawables, including selected content, masks, insets, and layer facts.

## 2.2.0

- Added bounded render semantics for backgrounds, foregrounds, tint colors,
  elevation, clipping, outlines, and supported drawable types.
- Added `GradientDrawable` shape, fill, gradient, and per-corner radius
  inspection while respecting platform API availability.
- Added `ConstraintLayout` match-constraint minimum, maximum, and dimension
  ratio relations.
- Added fail-closed password privacy across text inspection, semantic previews,
  node details, and temporary text mutations.

## 2.1.0

- Added contract-compliant UI graph relations to Android node-detail responses.
- Added logical dimension relations for exact `ViewGroup.LayoutParams` sizes
  while omitting semantic and weighted dimensions.
- Added `ConstraintLayout` parent and sibling anchor relations with logical
  margins, gone margins, and baseline semantics.
- Added `ConstraintLayout` percentage dimension relations against the parent
  content area.
- Updated the Runtime dependency to Astrolabe Protocol Kotlin 2.1.0.

## 2.0.1

- Lowered the published AAR compile SDK requirement from API 36.1 to API 35.
- Kept the Runtime minimum supported Android version at API 23.

## 2.0.0

- Added Android View hierarchy, node-detail, semantic attribute, and geometry
  inspection.
- Added emulator and USB device discovery through ADB forwarding.
- Added allowlisted in-memory presentation patches with rollback.
- Added automatic debug-process startup with no application code changes.
- Added one debug-only Fused AAR containing the public facade and internal
  Runtime modules.
- Added Maven Central release automation with signed artifacts, checksums, and
  release validation.
